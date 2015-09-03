package io.onthego.apps.aritutorial;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import io.onthego.android.camera.CameraOpenException;
import io.onthego.ari.android.ActiveAri;
import io.onthego.ari.android.Ari;
import io.onthego.ari.android.views.HandMotionEventView;
import io.onthego.ari.event.HandEvent;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.onthego.android.camera.CameraManager.getFirstCameraOrDefault;

public class TutorialActivity extends Activity
        implements Ari.StartCallback,
                   Ari.ErrorCallback,
                   HandEvent.Listener {

    private static final long STATE_TRANSITION_TIME_MS = 1500;
    private static final long HAND_SIGN_WAIT_TIME_NS = TimeUnit.SECONDS.toNanos(1)
            + TimeUnit.MILLISECONDS.toNanos(STATE_TRANSITION_TIME_MS);

    private enum Stage {
        INITIAL,
        TRANSITION,
        OPEN_HAND,
        CLOSED_HAND,
        V_SIGN,
        RIGHT_SWIPE,
        LEFT_SWIPE,
        SUCCESS_DIALOG,
        DONE
    }

    private static final ListMultimap<Stage, StageOption> STAGE_OPTIONS =
            new ImmutableListMultimap.Builder<Stage, StageOption>()
                    .putAll(Stage.INITIAL,
                            new StageOption(0, 0, HandEvent.Type.NO_HAND,
                                            false,
                                            Stage.OPEN_HAND))
                    .putAll(Stage.OPEN_HAND,
                            new StageOption(R.string.open_hand_instruction,
                                            R.drawable.tutorial_open_hand,
                                            HandEvent.Type.OPEN_HAND,
                                            true,
                                            Stage.CLOSED_HAND))
                    .putAll(Stage.CLOSED_HAND,
                            new StageOption(R.string.closed_hand_instruction,
                                            R.drawable.tutorial_closed_hand,
                                            HandEvent.Type.CLOSED_HAND,
                                            true,
                                            Stage.V_SIGN))
                    .putAll(Stage.V_SIGN,
                            new StageOption(R.string.v_sign_instruction,
                                            R.drawable.tutorial_v_sign,
                                            HandEvent.Type.V_SIGN,
                                            true,
                                            Stage.RIGHT_SWIPE))
                    .putAll(Stage.RIGHT_SWIPE,
                            new StageOption(R.string.right_swipe_instruction,
                                            R.drawable.tutorial_right_swipe,
                                            HandEvent.Type.RIGHT_SWIPE,
                                            true,
                                            Stage.LEFT_SWIPE))
                    .putAll(Stage.LEFT_SWIPE,
                            new StageOption(R.string.left_swipe_instruction,
                                            R.drawable.tutorial_left_swipe,
                                            HandEvent.Type.LEFT_SWIPE,
                                            true,
                                            Stage.SUCCESS_DIALOG))
                    .putAll(Stage.SUCCESS_DIALOG,
                            new StageOption(R.string.tutorial_exit,
                                            R.drawable.tutorial_closed_hand,
                                            HandEvent.Type.CLOSED_HAND,
                                            false,
                                            Stage.DONE),
                            new StageOption(R.string.tutorial_try_again,
                                            R.drawable.tutorial_open_hand,
                                            HandEvent.Type.OPEN_HAND,
                                            false,
                                            Stage.OPEN_HAND))
                    .build();

    private final Map<HandEvent.Type, Long> signTimestampsNs =
            Maps.newEnumMap(HandEvent.Type.class);

    private ActiveAri mAri;
    private TextView mTextView;
    private ImageView mImageView;
    private TextView mTextView2;
    private ImageView mImageView2;
    private boolean mMirrorImages;
    @Nonnull
    private Stage mStage = Stage.INITIAL;
    private Runnable mUiRunnable;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on during the tutorial
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_tutorial);

        final SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);

        final HandMotionEventView motionEventView =
                (HandMotionEventView) findViewById(R.id.handMotionEventView);
        motionEventView.hideEventLabels(HandEvent.Type.SWIPES);

        mTextView = (TextView) findViewById(R.id.tutorialTextView);
        mImageView = (ImageView) findViewById(R.id.tutorialImage);
        mTextView2 = (TextView) findViewById(R.id.tutorialTextView2);
        mImageView2 = (ImageView) findViewById(R.id.tutorialImage2);

        // Mirror the displayed hand images when the camera is facing towards the user
        // so that the displayed hand lines up with their actual hand.
        final int cameraId =
                getFirstCameraOrDefault(Camera.CameraInfo.CAMERA_FACING_FRONT, 0);
        final Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, cameraInfo);
        mMirrorImages = cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;

        mAri = ActiveAri.getInstanceForCameraId(getString(R.string.ari_license_key),
                                                this, cameraId)
                        .addListeners(this, motionEventView)
                        .setPreviewDisplay(surfaceView)
                        .addErrorCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mTextView.setVisibility(View.GONE);
        mImageView.setVisibility(View.GONE);
        if (mUiRunnable != null) {
            mTextView.removeCallbacks(mUiRunnable);
            if (mStage == Stage.TRANSITION) {
                // Events are not accepted in mid-transition,
                // so for the user to continue the tutorial the transition needs to be
                // completed.
                mUiRunnable.run();
            }
            mUiRunnable = null;
        }
        if (mAri != null) {
            mAri.stop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAri.start(this);
    }

    @Override
    public void onAriError(@Nonnull final Throwable throwable) {
        if (!tryHandleCameraError(this, throwable)) {
            throw new RuntimeException(throwable);
        }
    }

    @Override
    public void onAriStart() {
        mTextView.setVisibility(View.VISIBLE);
        mImageView.setVisibility(View.VISIBLE);
        if (mStage == Stage.INITIAL) {
            final long initialSignTimestamp = System.nanoTime() - HAND_SIGN_WAIT_TIME_NS;
            for (final HandEvent.Type type : HandEvent.Type.SIGNS) {
                signTimestampsNs.put(type, initialSignTimestamp);
            }
            // simulate the next event to transition out of INITIAL stage
            changeStage(STAGE_OPTIONS.get(mStage).get(0).eventType);
        }
    }

    /**
     * Finds the next tutorial stage based on {@code handEventType} and transitions to it.
     *
     * @param handEventType the type of the input hand gesture
     */
    private void changeStage(@Nonnull final HandEvent.Type handEventType) {
        checkNotNull(handEventType);

        mTextView2.setVisibility(View.GONE);
        mImageView2.setVisibility(View.GONE);

        final StageOption stageOption = findNextStageOption(mStage, handEventType);
        if (stageOption == null) {
            return;
        }

        if (stageOption.nextStage == Stage.DONE) {
            finish();
            return;
        }

        mAri.disable(HandEvent.Type.values());
        // Unmirror images in preparation for the next stage's image
        if (mImageView.getScaleX() < 0) {
            mImageView.setScaleX(1);
            mImageView2.setScaleX(1);
        }
        mTextView2.setVisibility(View.GONE);
        mImageView2.setVisibility(View.GONE);

        startStageTransition(stageOption.showTransition, stageOption.nextStage);
    }

    /**
     * Returns the options for the stage of the tutorial that should come after the
     * current stage when a gesture of {@code handEventType} is detected.
     *
     * @return the options for the next stage, or null if there is no stage transition
     *         defined from the current stage for the given gesture type
     */
    @Nullable
    private static StageOption findNextStageOption(
            @Nonnull final Stage currentStage,
            @Nonnull final HandEvent.Type handEventType) {
        checkNotNull(currentStage);
        checkNotNull(handEventType);

        for (final StageOption stageOption : STAGE_OPTIONS.get(currentStage)) {
            if (stageOption.eventType == handEventType) {
                return stageOption;
            }
        }
        return null;
    }

    /**
     * Updates the tutorial UI with the text and images associated with
     * {@code nextStage}. If {@code showTransition} is true, then a transition screen
     * is shown before displaying the next stage's UI.
     */
    private void startStageTransition(final boolean showTransition,
                                      @Nonnull final Stage nextStage) {
        checkNotNull(nextStage);

        mUiRunnable = new Runnable() {
            @Override
            public void run() {
                final List<StageOption> stageOptions = STAGE_OPTIONS.get(nextStage);
                final StageOption stageOption1 = stageOptions.get(0);
                final StageOption stageOption2 = stageOptions.size() > 1 ?
                                                 stageOptions.get(1) :
                                                 null;
                mTextView.setText(stageOption1.labelTextId);
                if (mMirrorImages && stageOption1.eventType.isSign()) {
                    mImageView.setScaleX(-1);
                }
                mImageView.setImageResource(stageOption1.drawableId);

                mAri.enable(stageOption1.eventType);

                if (stageOption1.eventType.isSwipe() ||
                        ((stageOption2 != null) && stageOption2.eventType.isSwipe())) {
                    mAri.enable(HandEvent.Type.SWIPE_PROGRESS);
                }

                if (stageOption2 != null) {
                    mTextView2.setText(stageOption2.labelTextId);
                    if (mMirrorImages && stageOption2.eventType.isSign()) {
                        mImageView2.setScaleX(-1);
                    }
                    mImageView2.setImageResource(stageOption2.drawableId);
                    mTextView2.setVisibility(View.VISIBLE);
                    mImageView2.setVisibility(View.VISIBLE);
                    mAri.enable(stageOption2.eventType);
                }

                mStage = nextStage;
            }
        };

        if (showTransition) {
            mStage = Stage.TRANSITION;
            mTextView.setText(R.string.tutorial_good);
            mImageView.setImageResource(R.drawable.tutorial_checkmark);
            mTextView.postDelayed(mUiRunnable, STATE_TRANSITION_TIME_MS);
        } else {
            mUiRunnable.run();
        }
    }

    @Override
    public void onHandEvent(@Nonnull final HandEvent handEvent) {
        if (handEvent.type.isSign()) {
            if ((handEvent.timestampNs <
                    (signTimestampsNs.get(handEvent.type) + HAND_SIGN_WAIT_TIME_NS))) {
                return;
            }
            signTimestampsNs.put(handEvent.type, handEvent.timestampNs);
        }
        changeStage(handEvent.type);
    }

    public static boolean tryHandleCameraError(@Nonnull final Context context,
                                               final Throwable throwable) {
        checkNotNull(context, "context can't be null");

        if (!(throwable instanceof CameraOpenException)) {
            return false;
        }

        showCameraErrorDialog((Activity) context);
        return true;
    }

    public static void showCameraErrorDialog(@Nonnull final Activity activity) {
        checkNotNull(activity, "activity can't be null");
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setMessage(R.string.camera_open_error)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(final DialogInterface dialog) {
                activity.finish();
            }
        });
        dialog.show();
    }

    @Immutable
    private static class StageOption {
        public final int labelTextId;
        public final int drawableId;
        public final boolean showTransition;
        @Nonnull
        public final HandEvent.Type eventType;
        @Nonnull
        public final Stage nextStage;

        StageOption(final int labelTextId,
                    final int drawableId,
                    @Nonnull final HandEvent.Type eventType,
                    final boolean showTransition,
                    @Nonnull final Stage nextStage) {
            this.labelTextId = labelTextId;
            this.drawableId = drawableId;
            this.eventType = checkNotNull(eventType);
            this.showTransition = showTransition;
            this.nextStage = checkNotNull(nextStage);
        }
    }
}
