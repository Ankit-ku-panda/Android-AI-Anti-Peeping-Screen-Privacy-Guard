package com.ankitkupanda.privacyguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.WindowManager;
import android.util.Size;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads low-resolution front-camera frames and reports an on-device face count. */
public final class FaceMonitor {
    public interface Listener {
        void onFaceCount(int faceCount);
        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final CameraManager cameraManager;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final Object cameraLock = new Object();

    private final FaceDetector detector;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private int sensorOrientation;
    private boolean frontFacing = true;
    private volatile boolean stopped;

    public FaceMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build();
        detector = FaceDetection.getClient(options);
    }

    public void start() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("Camera permission is not granted");
            return;
        }
        cameraThread = new HandlerThread("PrivacyGuardCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraHandler.post(this::openFrontCamera);
    }

    public void stop() {
        stopped = true;
        synchronized (cameraLock) {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        }
        detector.close();
        analysisExecutor.shutdownNow();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void openFrontCamera() {
        try {
            String cameraId = findFrontCameraId();
            if (cameraId == null) {
                listener.onError("No front camera was found");
                return;
            }

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;

            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                listener.onError("The front camera does not provide a usable stream");
                return;
            }
            Size size = chooseAnalysisSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (size == null) {
                listener.onError("The front camera has no YUV image stream");
                return;
            }

            imageReader = ImageReader.newInstance(
                    size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::analyzeLatestImage, cameraHandler);

            if (context.checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                listener.onError("Camera permission was removed");
                return;
            }
            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException exception) {
            listener.onError("Could not open the front camera: " + safeMessage(exception));
        }
    }

    private String findFrontCameraId() throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            Integer facing = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
        }
        return null;
    }

    private Size chooseAnalysisSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        final long targetArea = 640L * 480L;
        final long minimumArea = 480L * 360L;
        Size best = sizes[0];
        long bestDistance = Long.MAX_VALUE;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            if (area < minimumArea) {
                continue;
            }
            long distance = Math.abs(area - targetArea);
            if (distance < bestDistance) {
                best = size;
                bestDistance = distance;
            }
        }
        return best;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            if (stopped) {
                camera.close();
                return;
            }
            synchronized (cameraLock) {
                cameraDevice = camera;
            }
            createCaptureSession(camera);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            listener.onError("Front camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            listener.onError("Front camera error " + error);
        }
    };

    private void createCaptureSession(CameraDevice camera) {
        try {
            Surface surface = imageReader.getSurface();
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            request.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            camera.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (stopped) {
                                session.close();
                                return;
                            }
                            synchronized (cameraLock) {
                                captureSession = session;
                            }
                            try {
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                            } catch (CameraAccessException exception) {
                                listener.onError("Could not start camera frames: "
                                        + safeMessage(exception));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            listener.onError("The front camera stream could not be configured");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException exception) {
            listener.onError("Could not configure the front camera: " + safeMessage(exception));
        }
    }

    private void analyzeLatestImage(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        if (stopped || !processing.compareAndSet(false, true)) {
            image.close();
            return;
        }

        InputImage input = InputImage.fromMediaImage(image, imageRotationDegrees());
        detector.process(input)
                .addOnSuccessListener(analysisExecutor, faces -> listener.onFaceCount(faces.size()))
                .addOnFailureListener(analysisExecutor,
                        error -> listener.onError("Face detection failed: " + safeMessage(error)))
                .addOnCompleteListener(analysisExecutor, ignored -> {
                    image.close();
                    processing.set(false);
                });
    }

    @SuppressWarnings("deprecation")
    private int imageRotationDegrees() {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int displayRotation = manager.getDefaultDisplay().getRotation();
        int deviceDegrees;
        switch (displayRotation) {
            case Surface.ROTATION_90:
                deviceDegrees = 90;
                break;
            case Surface.ROTATION_180:
                deviceDegrees = 180;
                break;
            case Surface.ROTATION_270:
                deviceDegrees = 270;
                break;
            default:
                deviceDegrees = 0;
        }
        if (frontFacing) {
            return (sensorOrientation + deviceDegrees) % 360;
        }
        return (sensorOrientation - deviceDegrees + 360) % 360;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
