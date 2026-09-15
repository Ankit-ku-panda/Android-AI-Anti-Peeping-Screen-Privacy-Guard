package com.ankitkupanda.privacyguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
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
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads front-camera frames, detects faces, and creates local face signatures. */
public final class FaceMonitor {
    public interface Listener {
        void onFaceFrame(FaceFrame frame);
        void onError(String message);
    }

    public static final class FaceFrame {
        public final int faceCount;
        public final List<float[]> signatures;
        public final float primaryYaw;

        FaceFrame(int faceCount, List<float[]> signatures, float primaryYaw) {
            this.faceCount = faceCount;
            this.signatures = Collections.unmodifiableList(signatures);
            this.primaryYaw = primaryYaw;
        }
    }

    private static final long ANALYSIS_INTERVAL_MS = 160L;
    private static final int MIN_FACE_PIXELS = 96;

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
    private long lastAnalysisAt;

    public FaceMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build();
        detector = FaceDetection.getClient(options);
    }

    public void start() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
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
        long now = SystemClock.elapsedRealtime();
        if (stopped || now - lastAnalysisAt < ANALYSIS_INTERVAL_MS
                || !processing.compareAndSet(false, true)) {
            image.close();
            return;
        }
        lastAnalysisAt = now;

        GrayFrame frame;
        try {
            frame = GrayFrame.fromImage(image, imageRotationDegrees());
        } catch (RuntimeException error) {
            image.close();
            processing.set(false);
            listener.onError("Could not prepare camera frame: " + safeMessage(error));
            return;
        }
        image.close();

        Bitmap bitmap = frame.toBitmap();
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        detector.process(input)
                .addOnSuccessListener(analysisExecutor, faces -> {
                    List<float[]> signatures = new ArrayList<>();
                    Face primary = null;
                    int largestArea = -1;
                    for (Face face : faces) {
                        Rect box = face.getBoundingBox();
                        int area = box.width() * box.height();
                        if (area > largestArea) {
                            primary = face;
                            largestArea = area;
                        }
                        float[] signature = signatureForFace(frame, face);
                        if (signature != null) {
                            signatures.add(signature);
                        }
                    }
                    float yaw = primary == null ? 0f : primary.getHeadEulerAngleY();
                    listener.onFaceFrame(new FaceFrame(faces.size(), signatures, yaw));
                })
                .addOnFailureListener(analysisExecutor,
                        error -> listener.onError("Face detection failed: " + safeMessage(error)))
                .addOnCompleteListener(analysisExecutor, ignored -> {
                    bitmap.recycle();
                    processing.set(false);
                });
    }

    private float[] signatureForFace(GrayFrame frame, Face face) {
        Rect box = face.getBoundingBox();
        if (box.width() < MIN_FACE_PIXELS || box.height() < MIN_FACE_PIXELS
                || Math.abs(face.getHeadEulerAngleY()) > 32f
                || Math.abs(face.getHeadEulerAngleZ()) > 35f) {
            return null;
        }

        FaceLandmark leftEyeLandmark = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark rightEyeLandmark = face.getLandmark(FaceLandmark.RIGHT_EYE);
        if (leftEyeLandmark != null && rightEyeLandmark != null) {
            PointF leftEye = leftEyeLandmark.getPosition();
            PointF rightEye = rightEyeLandmark.getPosition();
            try {
                return FaceSignature.extractAligned(
                        frame.pixels, frame.width, frame.height,
                        leftEye.x, leftEye.y, rightEye.x, rightEye.y);
            } catch (IllegalArgumentException ignored) {
                // Rare weak landmarks fall back to the detector's face box.
            }
        }

        int side = Math.round(Math.max(box.width(), box.height()) * 1.10f);
        int centerX = box.centerX();
        int centerY = box.centerY();
        int left = centerX - side / 2;
        int top = centerY - side / 2;
        return FaceSignature.extract(frame.pixels, frame.width, frame.height,
                left, top, left + side, top + side);
    }

    private int imageRotationDegrees() {
        android.view.WindowManager manager = (android.view.WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
        @SuppressWarnings("deprecation")
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

    private static final class GrayFrame {
        final byte[] pixels;
        final int width;
        final int height;

        GrayFrame(byte[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }

        static GrayFrame fromImage(Image image, int rotation) {
            int sourceWidth = image.getWidth();
            int sourceHeight = image.getHeight();
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer().duplicate();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int outputWidth = rotation == 90 || rotation == 270 ? sourceHeight : sourceWidth;
            int outputHeight = rotation == 90 || rotation == 270 ? sourceWidth : sourceHeight;
            byte[] output = new byte[outputWidth * outputHeight];

            for (int sourceY = 0; sourceY < sourceHeight; sourceY++) {
                int row = sourceY * rowStride;
                for (int sourceX = 0; sourceX < sourceWidth; sourceX++) {
                    byte value = buffer.get(row + sourceX * pixelStride);
                    int destinationX;
                    int destinationY;
                    if (rotation == 90) {
                        destinationX = sourceHeight - 1 - sourceY;
                        destinationY = sourceX;
                    } else if (rotation == 180) {
                        destinationX = sourceWidth - 1 - sourceX;
                        destinationY = sourceHeight - 1 - sourceY;
                    } else if (rotation == 270) {
                        destinationX = sourceY;
                        destinationY = sourceWidth - 1 - sourceX;
                    } else {
                        destinationX = sourceX;
                        destinationY = sourceY;
                    }
                    output[destinationY * outputWidth + destinationX] = value;
                }
            }
            return new GrayFrame(output, outputWidth, outputHeight);
        }

        Bitmap toBitmap() {
            int[] colors = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int value = pixels[i] & 0xff;
                colors[i] = Color.rgb(value, value, value);
            }
            return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
        }
    }
}
