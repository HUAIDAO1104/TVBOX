package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.AttributeSet;
import android.view.KeyEvent;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;
import java.util.Locale;

public class CustomMic extends AppCompatImageView {

    private ActivityResultLauncher<Intent> mLauncher;
    private CustomTextListener mListener;
    private SpeechRecognizer mRecognizer;
    private FragmentActivity mActivity;
    private boolean mAvailable;
    private boolean mExternalAvailable;
    private boolean mListen;

    public CustomMic(@NonNull Context context) {
        super(context);
    }

    public CustomMic(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    private boolean isAvailable() {
        return mAvailable;
    }

    public boolean isListening() {
        return mListen;
    }

    private Intent getIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getContext().getString(R.string.search_v2_voice_prompt));
        return intent;
    }

    public void setListener(FragmentActivity activity, CustomTextListener listener) {
        mActivity = activity;
        mListener = listener;
        mListener.setDone(() -> updateUI(false));
        mAvailable = SpeechRecognizer.isRecognitionAvailable(activity);
        mExternalAvailable = hasResolveActivity();
        initSpeech();
    }

    private void initSpeech() {
        setVisibility(VISIBLE);
        if (isAvailable()) initRecognizer();
        if (mExternalAvailable) initLauncher();
    }

    private boolean hasResolveActivity() {
        return getIntent().resolveActivity(mActivity.getPackageManager()) != null;
    }

    private void initRecognizer() {
        try {
            if (mRecognizer == null) mRecognizer = SpeechRecognizer.createSpeechRecognizer(mActivity);
            mRecognizer.setRecognitionListener(mListener);
        } catch (RuntimeException ignored) {
            mRecognizer = null;
            mAvailable = false;
        }
    }

    private void initLauncher() {
        mLauncher = mActivity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            updateUI(false);
            if (result.getResultCode() != Activity.RESULT_OK) {
                mListener.onCancelled();
                return;
            }
            if (result.getData() == null) {
                mListener.onNoMatch();
                return;
            }
            List<String> texts = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String text = com.fongmi.android.tv.ui.search.VoiceSearchPolicy.firstResult(texts);
            if (text.isEmpty()) mListener.onNoMatch();
            else mListener.onResults(text);
        });
    }

    public void start() {
        if (mActivity == null) return;
        if (isListening()) {
            stop();
            return;
        }
        if (isAvailable() && mRecognizer != null) startRecognizer();
        else if (mLauncher != null) launchIntent();
        else mListener.onUnavailable();
    }

    private void startRecognizer() {
        if (mRecognizer == null) return;
        PermissionUtil.requestAudio(mActivity, allGranted -> {
            if (allGranted) startListening();
            else mListener.onPermissionDenied();
        });
    }

    private void startListening() {
        try {
            mRecognizer.startListening(getIntent());
            requestFocus();
            updateUI(true);
        } catch (Exception ignored) {
            updateUI(false);
            if (mLauncher != null) launchIntent();
            else mListener.onFailure(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    private void launchIntent() {
        try {
            if (mLauncher == null) {
                mListener.onUnavailable();
                return;
            }
            updateUI(true);
            mLauncher.launch(getIntent());
        } catch (Exception ignored) {
            updateUI(false);
            mListener.onFailure(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    public void stop() {
        if (!isListening()) return;
        if (mRecognizer != null) mRecognizer.cancel();
        updateUI(false);
    }

    public void destroy() {
        if (mRecognizer != null) {
            mRecognizer.destroy();
            mRecognizer = null;
        }
        if (mLauncher != null) {
            mLauncher.unregister();
            mLauncher = null;
        }
    }

    private void updateUI(boolean listening) {
        mListen = listening;
        if (listening) {
            startAnimation(ResUtil.getAnim(R.anim.flicker));
            setColorFilter(ContextCompat.getColor(getContext(), R.color.tv_accent_focused), PorterDuff.Mode.SRC_IN);
        } else {
            clearAnimation();
            setColorFilter(ContextCompat.getColor(getContext(), R.color.tv_text_primary), PorterDuff.Mode.SRC_IN);
        }
    }

    private boolean onBackKey(KeyEvent event) {
        if (!isListening() || !KeyUtil.isBackKey(event)) return false;
        stop();
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (onBackKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
