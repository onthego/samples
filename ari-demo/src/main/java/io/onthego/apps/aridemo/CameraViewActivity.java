package io.onthego.apps.aridemo;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;

import javax.annotation.Nonnull;

import io.onthego.ari.KeyDecodingException;
import io.onthego.ari.android.ActiveAri;
import io.onthego.ari.android.Ari;
import io.onthego.ari.android.views.HandMotionEventView;
import io.onthego.ari.android.views.HandSignEventView;
import io.onthego.ari.event.HandEvent;

public class CameraViewActivity extends Activity
        implements Ari.StartCallback,
                   Ari.ErrorCallback,
                   HandEvent.Listener {

    private static final String TAG = "CameraViewActivity";

    private ActiveAri mAri;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_camera_view);

        // Hand event view overlays
        final HandSignEventView handSignEventView =
                (HandSignEventView) findViewById(R.id.handSignEventView);
        final HandMotionEventView handMotionEventView =
                (HandMotionEventView) findViewById(R.id.handMotionEventView);
        handSignEventView.setDrawable(HandSignEventView.DEFAULT_SIGN_DRAWABLE,
                                      HandEvent.Type.SIGNS)
                         .showEventLabels(HandEvent.Type.SIGNS);
        handMotionEventView.showEventLabels(HandEvent.Type.SWIPES);

        final SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);

        try {
            mAri = ActiveAri.getInstance(getString(R.string.ari_license_key), this)
                            .addListeners(this, handSignEventView, handMotionEventView)
                            .setPreviewDisplay(surfaceView)
                            .addErrorCallback(this);
        } catch (final KeyDecodingException e) {
            Log.e(TAG, "Failed to init Ari: ", e);
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

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
    public void onConfigurationChanged(final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mAri.setDisplayRotation(getWindowManager().getDefaultDisplay().getRotation());
    }

    @Override
    public void onAriStart() {
        // Enabling and disabling gestures is only available with Indie Developer and
        // Enterprise licenses.
        //mAri.disable(HandEvent.Type.values())
        //    .enable(HandEvent.Type.OPEN_HAND, HandEvent.Type.CLOSED_HAND,
        //            HandEvent.Type.LEFT_SWIPE, HandEvent.Type.RIGHT_SWIPE,
        //            HandEvent.Type.UP_SWIPE, HandEvent.Type.DOWN_SWIPE,
        //            HandEvent.Type.SWIPE_PROGRESS);

        Toast.makeText(this, getString(R.string.toast_ari_ready), Toast.LENGTH_SHORT)
             .show();
    }

    @Override
    public void onAriError(@Nonnull final Throwable throwable) {
        final String msg = "Ari error";
        Log.e(TAG, msg, throwable);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onHandEvent(@Nonnull final HandEvent handEvent) {
        Log.i(TAG, "Ari " + handEvent);
    }
}
