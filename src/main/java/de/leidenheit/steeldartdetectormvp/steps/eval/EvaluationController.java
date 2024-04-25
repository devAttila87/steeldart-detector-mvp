package de.leidenheit.steeldartdetectormvp.steps.eval;

import de.leidenheit.steeldartdetectormvp.FxUtil;
import de.leidenheit.steeldartdetectormvp.detection.*;
import de.leidenheit.steeldartdetectormvp.steps.ContentWithCameraController;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.util.Pair;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.BackgroundSubtractorMOG2;
import org.opencv.video.Video;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.text.MessageFormat;
import java.util.Arrays;

public class EvaluationController extends ContentWithCameraController {

    @FXML
    public TextField txtScoreFirst;
    @FXML
    public TextField txtScoreSecond;
    @FXML
    public TextField txtScoreThird;
    @FXML
    public TextArea textAreaLog;
    @FXML
    public Button buttonReplay;
    @FXML
    public Button buttonResetScore;
    @FXML
    public Label labelFeedInfo;
    @FXML
    public Label labelCameraInfo;

    private BackgroundSubtractorMOG2 subtractor;
    private Mat ref = new Mat();
    private final int[] scoreArray = new int[3];
    // used to determine unplugging of darts
    private boolean skipUntilDiffZero = false;

    @Override
    protected void onDetectionTaskFailed() {
        log("Eval Step: Detection task failed");
        ref.release();
        enableControls(true);
    }

    @Override
    protected void onDetectionTaskCancelled() {
        log("Eval Step: Detection task cancelled");
        ref.release();
        enableControls(true);
    }

    @Override
    protected void onDetectionTaskSucceeded() {
        log("Eval Step: Detection task succeeded");
        ref.release();
        enableControls(true);
    }

    @Override
    protected void onDetectionTaskRunning() {
        log("Eval Step: Detection task running");
        ref.release();
        enableControls(false);
    }

    @Override
    protected void takeSnapshot() {
        // ignored for now
    }

    @Override
    protected void customInit() {
        log("Eval Step: Initialize evaluation");

        // handlers
        buttonResetScore.setOnAction(event -> resetScore());
        buttonReplay.setOnAction(event -> initialize());

        resetScore();

        // reset skip flag
        skipUntilDiffZero = false;
    }

    private boolean shouldSkipFrame() {
        boolean isFirstFrame = framePos <= 1;
        return isFirstFrame || ref.empty();
    }

    private void tryApplyReferenceImage(final Mat frame) {
        if ((int) framePos == (int) fps) {
            ref = frame.clone();
            Imgproc.cvtColor(ref, ref, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(ref, ref, new Size(11, 11), 0);
        }
    }

    private void resizeAndGaussFrame(final Mat frame, final double resizeScaleFactor, final int gauss) {
        Imgproc.resize(frame, frame, new Size(resolutionWidth * resizeScaleFactor, resolutionHeight * resizeScaleFactor));
        Imgproc.GaussianBlur(frame, frame, new Size(gauss, gauss), 0);
    }

    private boolean checkDiffZero(final Mat referenceFrame, final Mat frame) {
        Mat compareFrame = frame.clone();
        Imgproc.cvtColor(compareFrame, compareFrame, Imgproc.COLOR_BGR2GRAY);
        Imgproc.GaussianBlur(compareFrame, compareFrame, new Size(11, 11), 0);
        Mat differenceFrame = new Mat();
        Core.absdiff(referenceFrame, compareFrame, differenceFrame);
        Mat thresholdFrame = new Mat();
        Imgproc.threshold(differenceFrame, thresholdFrame, 50, 255, Imgproc.THRESH_BINARY);
        return Core.countNonZero(thresholdFrame) == 0;
    }

    private void handleDeepFrameEvaluation(final int framePosFrom, final int framePosTo) {
        Mat nextFrame = new Mat();
        Mat nextMask = new Mat();
        Mat maskToEval = new Mat();
        // evaluate the next frames in order to find the one that fits the evaluation requirements
        for (int i = framePosFrom; i < framePosTo; i++) {
            if (CamSingleton.getInstance().getVideoCapture().read(nextFrame)) {
                resizeAndGaussFrame(nextFrame, calculatedScaleFactor, DartSingleton.getInstance().vidGaussian);

                subtractor.apply(nextFrame, nextMask);
                int countNonZeroPixels = Core.countNonZero(nextMask);

                // most likely in motion
                if (Detection.hasSignificantChanges(nextMask, 10_000, 30_000)) {
                    log("Eval Step: [frame={0}] Seems to be in motion (diff={1}); ignored", i, countNonZeroPixels);
                    continue;
                }
                // most likely unplugging
                if (Detection.hasSignificantChanges(nextMask, 30_001, Integer.MAX_VALUE)) {
                    log("Eval Step: [frame={0}] Seems to be unplugging darts (diff={1})", i, countNonZeroPixels);
                    log("Skipping due to zero difference threshold");
                    // waits in the main loop for the frame to be reached before doing any other evaluation
                    skipUntilDiffZero = true;
                    maskToEval.release();
                    return;
                }
                // evaluation of candidate
                boolean significantChanges = Detection.hasSignificantChanges(nextMask, 0, 50);
                if (!maskToEval.empty() && significantChanges) {
                    // merge found dart contours into a single one
                    MatOfPoint mergedContour = morphMergeContours(maskToEval);
                    double contourArea = Imgproc.contourArea(mergedContour);
                    if (contourArea > DartSingleton.getInstance().vidMaxMergedContourArea) {
                        log("Skipping since merged contour is larger than threshold");
                        maskToEval.release();
                        return;
                    }
                    // calculate bounding rectangle
                    Rect boundingRect = Imgproc.boundingRect(mergedContour);
                    // calculate aspect ratio
                    double aspectRatio = (double) boundingRect.width / boundingRect.height;
                    // check if aspect ratio falls within desired range (45° to 90°)
                    if (aspectRatio >= 0.25 && aspectRatio <= 2.0) {
                        // evaluation of dart arrow
                        log("Dart Arrow Detected: start evaluation at frame {0} with aspect ratio {1}", framePos, aspectRatio);
                        Pair<Pair<Integer, Mat>, Point[]> scoreAndDartTipBoundingBoxPair = evaluateDartContour(nextFrame, mergedContour);

                        // draw hit segment mask
                        try {
                            Point center = Detection.findCircleCenter(nextFrame, MaskSingleton.getInstance().innerBullMask);
                            Imgproc.circle(nextFrame, center, 1, new Scalar(255, 0, 0), -1);
                            double angle = Detection.calculateAngle(center, scoreAndDartTipBoundingBoxPair.getValue()[0]);
                            Point firstPoint = new Point(resolutionWidth, center.y);
                            var segment = MaskSingleton.getInstance().valueAngleRanges.getValueAngleRangeMap().entrySet().stream()
                                    .filter(entry -> angle >= entry.getKey().minValue() && angle <= entry.getKey().maxValue())
                                    .findFirst()
                                    .orElse(null);
                            if (segment != null) {
                                double radianMin = Math.toRadians(segment.getKey().minValue());
                                double radianMax = Math.toRadians(segment.getKey().maxValue());
                                Point newPointMin = Detection.rotatePoint(center, firstPoint, radianMin);
                                Point newPointMax = Detection.rotatePoint(center, firstPoint, radianMax);
                                Imgproc.line(nextFrame, center, newPointMin, new Scalar(0, 255, 0), 2);
                                Imgproc.line(nextFrame, center, newPointMax, new Scalar(200, 0, 0), 2);
                            }
                        } catch (LeidenheitException e) {
                            throw new RuntimeException(e);
                        }
                        applyDebugDetails(nextFrame, scoreAndDartTipBoundingBoxPair);
                    } else {
                        log("Skipping due to aspect ratio threshold");
                        maskToEval.release();
                        return;
                    }
                    maskToEval.release();
                    return;
                }
            }

            // apply candidate for next iteration
            if (Detection.hasSignificantChanges(nextMask, 1_000, 30_000)) {
                maskToEval.release();
                maskToEval = nextMask.clone();
            }
        }
    }

    private Mat extractHitMask(final Mat frame, final Pair<Pair<Integer, Mat>, Point[]> scoreTipPair) {
        Mat res = new Mat();
        Core.bitwise_and(frame, frame, res, scoreTipPair.getKey().getValue());
        return res;
    }

    private void applyDebugDetails(final Mat frame, final Pair<Pair<Integer, Mat>, Point[]> scoreAndDartTipBoundingBoxPair) {
        // draw bounding rectangle
        Mat image = frame.clone();
        try {
            image = extractHitMask(image, scoreAndDartTipBoundingBoxPair);
            if (!image.empty()) {

                Imgproc.rectangle(
                        image,
                        scoreAndDartTipBoundingBoxPair.getValue()[1], // tl
                        scoreAndDartTipBoundingBoxPair.getValue()[2], // br
                        new Scalar(125, 125, 20), 2);
                Imgproc.circle(image, scoreAndDartTipBoundingBoxPair.getValue()[3], 3, new Scalar(0, 255, 0), -1);
                Imgproc.circle(image, scoreAndDartTipBoundingBoxPair.getValue()[0], 3, new Scalar(0, 0, 255), -1);

                Mat finalImage = image.clone();
                image.release();
                Platform.runLater(() -> {
                    imageView.setImage(FxUtil.matToImage(finalImage));
                    finalImage.release();
                });
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private MatOfPoint morphMergeContours(final Mat maskToEval) {
        Imgproc.morphologyEx(maskToEval, maskToEval, Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3)),
                new Point(-1, -1),
                DartSingleton.getInstance().vidCloseIterations);
        Imgproc.morphologyEx(maskToEval, maskToEval, Imgproc.MORPH_DILATE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5)),
                new Point(-1, -1),
                DartSingleton.getInstance().vidDilateIterations);
        Imgproc.morphologyEx(maskToEval, maskToEval, Imgproc.MORPH_ERODE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5)),
                new Point(-1, -1),
                DartSingleton.getInstance().vidErodeIterations);

        return Detection.extractMergedContour(maskToEval, DartSingleton.getInstance().vidMinContourArea);
    }

    private Pair<Pair<Integer, Mat>, Point[]> evaluateDartContour(final Mat frame, final MatOfPoint dartContour) {
        MatOfPoint convexHull = Detection.findConvexHull(dartContour);
        Point[] tipAndBB = Detection.findArrowTip(convexHull);
        Pair<Integer, Mat> scoreMaskPair = new Pair<>(0, new Mat());
        try {
            if (!Detection.pointIntersectsMask(tipAndBB[0], MaskSingleton.getInstance().dartboardMask)) {
                log("Eval Step: Ignored since not in dartboard mask");
            } else {
                Point center = Detection.findCircleCenter(frame, MaskSingleton.getInstance().innerBullMask);
                double angle = Detection.calculateAngle(center, tipAndBB[0]);
                log("Eval Step: Detected angle of tip of dart: {0} @ frame {1}", angle, framePos);
                scoreMaskPair = Detection.evaluatePoint(
                        MaskSingleton.getInstance().valueAngleRanges,
                        angle,
                        tipAndBB[0],
                        MaskSingleton.getInstance().dartboardMask,
                        MaskSingleton.getInstance().innerBullMask,
                        MaskSingleton.getInstance().outerBullMask,
                        MaskSingleton.getInstance().tripleMask,
                        MaskSingleton.getInstance().doubleMask,
                        MaskSingleton.getInstance().singleMask);
            }
            if (scoreArray[0] == -1) {
                scoreArray[0] = scoreMaskPair.getKey();
            } else if (scoreArray[1] == -1) {
                scoreArray[1] = scoreMaskPair.getKey();
            } else {
                scoreArray[2] = scoreMaskPair.getKey();
            }
            return new Pair<>(scoreMaskPair, tipAndBB);
        } catch (LeidenheitException e) {
            throw new RuntimeException(e);
        } finally {
            convexHull.release();
        }
    }

    @Override
    protected Mat customHandleFrame(final Mat frame) {
        tryApplyReferenceImage(frame);
        if (shouldSkipFrame()) return frame;

        // detection
        Mat mask = new Mat();
        try {
            subtractor.apply(frame, mask);
            if (skipUntilDiffZero && Core.countNonZero(mask) > 0) return frame;
            if (skipUntilDiffZero && checkDiffZero(ref, frame)) {
                skipUntilDiffZero = false;
                log("Skipping due to zero difference threshold done");
                resetScore();
            }
            boolean significantChanges = Detection.hasSignificantChanges(mask, 1_000, 30_000);
            if (significantChanges) {
                int framePosToStart = (int) framePos;
                int framePosToEnd = frameCount == -1 ? framePosToStart + (int) (fps) : Math.min(framePosToStart + (int) (fps), (int) frameCount);
                // nested loop deep evaluation
                handleDeepFrameEvaluation(framePosToStart, framePosToEnd);
            }
        } finally {
            mask.release();
            updateUI();
        }
        return frame.clone();
    }

    private void updateUI() {
        Platform.runLater(() -> {
            updateInfoInUI();
            updateLogInUI();
            updateScoreInUI();
        });
    }

    private void updateInfoInUI() {
        labelFeedInfo.setText(MessageFormat.format("Frame: {0}/{1} @ {2} FPS", framePos, frameCount, fps));
        labelCameraInfo.setText(MessageFormat.format("Camera: {0} @ Index [{1}] with {2}x{3}",
                CamSingleton.getInstance().getCameraName(),
                CamSingleton.getInstance().getSelectedCameraIndex(),
                resolutionWidth * calculatedScaleFactor,
                resolutionHeight * calculatedScaleFactor));
    }

    @Override
    protected VideoCapture cameraInit(VideoCapture videoCapture) {
        videoCapture.open(CamSingleton.getInstance().getSelectedCameraIndex(), Videoio.CAP_DSHOW);
        if (!videoCapture.isOpened()) {
            videoCapture.open(FxUtil.retrieveResourceAsTempFile("static/video", "gameplay.mp4").getAbsolutePath());
            if (!videoCapture.isOpened()) {
                log("Warning: video capture seems not to be ready...");
            }
        }
        subtractor = Video.createBackgroundSubtractorMOG2();
        subtractor.setVarThreshold(DartSingleton.getInstance().vidSubtractorThreshold);
        subtractor.setDetectShadows(false);
        subtractor.setHistory(frameCount == -1d ? (int) fps : (frameCount == 6 ? 1 : 2));
        return videoCapture;
    }

    private void enableControls(boolean enabled) {
        buttonReplay.setDisable(!enabled);
    }

    private void updateLogInUI() {
        textAreaLog.setText(String.join("\n", DartSingleton.getInstance().getDebugList()));
        textAreaLog.setScrollTop(Double.MAX_VALUE);
    }

    private void updateScoreInUI() {
        txtScoreFirst.setText(scoreArray[0] >= 0 ? String.valueOf(scoreArray[0]) : "-");
        txtScoreSecond.setText(scoreArray[1] >= 0 ? String.valueOf(scoreArray[1]) : "-");
        txtScoreThird.setText(scoreArray[2] >= 0 ? String.valueOf(scoreArray[2]) : "-");
    }

    private void resetScore() {
        log("Resetting score");
        Arrays.fill(scoreArray, -1);
        Platform.runLater(this::updateUI);
    }
}
