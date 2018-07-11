/*
 * Copyright (C) 2008-2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ugrokit.softkeyboard;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.support.annotation.ColorInt;
import android.text.InputType;
import android.text.method.MetaKeyKeyListener;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import com.ugrokit.api.*;
import com.ugrokit.api.Ugi.ConnectionStateListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Example of writing an input method for a soft keyboard.  This code is
 * focused on simplicity over completeness, so it should in no way be considered
 * to be a complete soft keyboard implementation.  Its purpose is to provide
 * a basic example for how you would get started writing an input method, to
 * be fleshed out as appropriate.
 */
public class SoftKeyboard extends InputMethodService
implements KeyboardView.OnKeyboardActionListener,
           ConnectionStateListener,
           UgiInventoryDelegate,
           UgiInventoryDelegate.InventoryTagFoundListener,
           UgiInventoryDelegate.InventoryDidStopListener
{

  /**
   * This boolean indicates the optional example code for performing
   * processing of hard keys in addition to regular text generation
   * from on-screen interaction.  It would be used for input methods that
   * perform language translations (such as converting text entered on
   * a QWERTY keyboard to Chinese), but may not be used for input methods
   * that are primarily intended to be used for on-screen text entry.
   */
  static final boolean PROCESS_HARD_KEYS = true;

  static final String PREFERENCES_KEY = "com.ugrokit.softkeyboard";
  static final String VOLUME_PREFERENCE_KEY = "volumePercentage";
  static final String POWER_PREFERENCE_KEY = "powerPercentage";
  static final String FIND_ONE_PREFERENCE_KEY = "findOneTagOnly";
  static final String STAY_CONNECTED_PREFERENCE_KEY = "stayConnectedAlways";
  static final String ASCII_PREFERENCE_KEY = "epcAscii";
  static final String ALWAYS_ADD_COMMA_AT_START_PREFERENCE_KEY = "alwaysAddCommaAtStart";

  private static final String TAG = "SoftKeyboard";

  private InputMethodManager mInputMethodManager;

  private LatinKeyboardView mInputView;
  private CandidateView mCandidateView;
  private CompletionInfo[] mCompletions;

  private StringBuilder mComposing = new StringBuilder();
  private boolean mPredictionOn;
  private boolean mHandleShift = false;
  private boolean mCompletionOn;
  private int mLastDisplayWidth;
  private boolean mCapsLock;
  private long mLastShiftTime;
  private long mMetaState;

  private LatinKeyboard mSymbolsKeyboard;
  private LatinKeyboard mSymbolsShiftedKeyboard;
  private LatinKeyboard mQwertyKeyboard;

  private LatinKeyboard mCurKeyboard;

  private String mWordSeparators;

  private UgiActivity mUgiActivity;
  private boolean mFirstEpc;
  private boolean mStoppingInventory = false;
  private AlertDialog mPowerVolumeDialog = null;

  private int mPowerValue = 100;
  private int mVolumeValue = 100;
  private boolean mFindOne = false;
  private boolean mStayConnected = false;
  private boolean mAscii = false;
  private boolean mAlwaysAddCommaAtStart = false;

  private static Ugi getUgi() {
    return SoftKeyboardApp.getUgi();
  }



  private class SoftKeyboardUiDelegate implements UgiUiUtil.UiDelegate {
    private View mApplicationView = null;
    private SoftKeyboard mContext = null;

    void setApplicationContextAndView (SoftKeyboard softKeyboard, View applicationView) {
      mContext = softKeyboard;
      mApplicationView = applicationView;
    }

    @Override
    public @ColorInt int getUiThemeColor() {
      return Color.parseColor("#446bcd");
    }

    @Override
    public @ColorInt int getUiTextColorOnThemeColor() { return Color.WHITE; }

    @Override
    public void showDialog(Dialog dialog) {
      Window window = dialog.getWindow();
      WindowManager.LayoutParams lp = window.getAttributes();
      lp.token = (mApplicationView != null) ? mApplicationView.getWindowToken() : null;
      lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
      window.setAttributes(lp);
      window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
      dialog.show();
    }

    @Override
    public Context getContext() {
      return mContext;
    }

  }



  @SuppressLint("StaticFieldLeak")
  private static SoftKeyboardUiDelegate sUiDelegate;

  /**
   * Main initialization of the input method component.  Be sure to call
   * to super class.
   */
  @Override public void onCreate() {
    super.onCreate();
    mInputMethodManager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
    mWordSeparators = getResources().getString(R.string.word_separators);

    sUiDelegate = new SoftKeyboardUiDelegate();
    UgiUiUtil.setUiDelegate(sUiDelegate);
    SoftKeyboardApp.setUiContext(this);

    mUgiActivity = new UgiActivity() {
      @Override
      public Ugi getUgi () {
        return SoftKeyboardApp.getUgi();
      }

      @Override
      public boolean ugiShouldHandleRotation () {
        return false;
      }
    };

    // Tell the Ugi singleton that there's an active activity.
    SoftKeyboardApp.getUgi().openConnection();
    SoftKeyboardApp.getUgi().activityOnCreate(this.mUgiActivity, false, false);
    SoftKeyboardApp.getUgi().addConnectionStateListener(this);

    Log.i(TAG, "onCreate called");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    getUgi().activityOnDestroy(this.mUgiActivity);
    SoftKeyboardApp.getUgi().closeConnection();
    Log.i(TAG, "onDestroy called");
  }

  /**
   * This is the point where you can do all of your UI initialization.  It
   * is called after creation and any configuration change.
   */
  @Override public void onInitializeInterface() {
    if (mQwertyKeyboard != null) {
      // Configuration changes can happen after the keyboard gets recreated,
      // so we need to be able to re-build the keyboards if the available
      // space has changed.
      int displayWidth = getMaxWidth();
      if (displayWidth == mLastDisplayWidth) return;
      mLastDisplayWidth = displayWidth;
    }
    mQwertyKeyboard = new LatinKeyboard(this, R.xml.qwerty);
    mSymbolsKeyboard = new LatinKeyboard(this, R.xml.symbols);
    mSymbolsShiftedKeyboard = new LatinKeyboard(this, R.xml.symbols_shift);

    Log.i(TAG, "onInitializeInterface: added Ugi connection state listener");

  }

  private void getSavedPreferences () {
    SharedPreferences preferences = getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE);
    if (preferences == null) {
      return;
    }

    this.mVolumeValue = preferences.getInt(VOLUME_PREFERENCE_KEY, 100);
    this.mPowerValue = preferences.getInt(POWER_PREFERENCE_KEY, 100);
    this.mFindOne = preferences.getBoolean(FIND_ONE_PREFERENCE_KEY, false);
    this.mStayConnected = preferences.getBoolean(STAY_CONNECTED_PREFERENCE_KEY, true);
    this.mAscii = preferences.getBoolean(ASCII_PREFERENCE_KEY, true);
    this.mAlwaysAddCommaAtStart = preferences.getBoolean(ALWAYS_ADD_COMMA_AT_START_PREFERENCE_KEY, false);
    getUgi().setDelayBeforeClosingConnectionOnInactivityMsec(this.mStayConnected ? 0 : 100);
  }

  private void savePreferences () {
    SharedPreferences preferences = getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE);
    SharedPreferences.Editor editor = preferences.edit();

    editor.putInt(VOLUME_PREFERENCE_KEY, this.mVolumeValue);
    editor.putInt(POWER_PREFERENCE_KEY, this.mPowerValue);
    editor.putBoolean(FIND_ONE_PREFERENCE_KEY, this.mFindOne);
    editor.putBoolean(STAY_CONNECTED_PREFERENCE_KEY, this.mStayConnected);
    editor.putBoolean(ASCII_PREFERENCE_KEY, this.mAscii);
    editor.putBoolean(ALWAYS_ADD_COMMA_AT_START_PREFERENCE_KEY, this.mAlwaysAddCommaAtStart);
    editor.apply();
  }

  /**
   * Called by the framework when your view for creating input needs to
   * be generated.  This will be called the first time your input method
   * is displayed, and every time it needs to be re-created such as due to
   * a configuration change.
   */
  @Override public View onCreateInputView() {
    mInputView = (LatinKeyboardView) getLayoutInflater().inflate(R.layout.input, null);
    mInputView.setOnKeyboardActionListener(this);
    mInputView.setKeyboard(mQwertyKeyboard);
    mInputView.setPreviewEnabled(false);
    sUiDelegate.setApplicationContextAndView(this, mInputView);

    getSavedPreferences();
    mPowerVolumeDialog = buildPowerVolumeDialog();

    Log.i(TAG, "onCreateInputView called");

    return mInputView;
  }

  /**
   * Called by the framework when your view for showing candidates needs to
   * be generated, like {@link #onCreateInputView}.
   */
  @Override public View onCreateCandidatesView() {
    mCandidateView = new CandidateView(this);
    mCandidateView.setService(this);
    return mCandidateView;
  }

  /**
   * This is the main point where we do our initialization of the input method
   * to begin operating on an application.  At this point we have been
   * bound to the client, and are now receiving all of the detailed information
   * about the target of our edits.
   */
  @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
    super.onStartInput(attribute, restarting);

    //Log.i(TAG, "onStartInput, current application: " + this.getApplication());

    // Reset our state.  We want to do this even if restarting, because
    // the underlying state of the text editor could have changed in any way.
    mComposing.setLength(0);
    updateCandidates();

    if (!restarting) {
      // Clear shift states.
      mMetaState = 0;
    }

    mPredictionOn = false;
    mCompletionOn = false;
    mCompletions = null;

    // We are now going to initialize our state based on the type of
    // text being edited.
    switch (attribute.inputType & InputType.TYPE_MASK_CLASS) {
      case InputType.TYPE_CLASS_NUMBER:
      case InputType.TYPE_CLASS_DATETIME:
        // Numbers and dates default to the symbols keyboard, with
        // no extra features.
        mCurKeyboard = mSymbolsKeyboard;
        break;

      case InputType.TYPE_CLASS_PHONE:
        // Phones will also default to the symbols keyboard, though
        // often you will want to have a dedicated phone keyboard.
        mCurKeyboard = mSymbolsKeyboard;
        break;

      case InputType.TYPE_CLASS_TEXT:
        // This is general text editing.  We will default to the
        // normal alphabetic keyboard, and assume that we should
        // be doing predictive text (showing candidates as the
        // user types).
        mCurKeyboard = mQwertyKeyboard;
        mHandleShift = true;

        // We now look for a few special variations of text that will
        // modify our behavior.
        int variation = attribute.inputType & InputType.TYPE_MASK_VARIATION;
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
          // Do not display predictions / what the user is typing
          // when they are entering a password.
          mPredictionOn = false;
        }

        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            || variation == InputType.TYPE_TEXT_VARIATION_URI
            || variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
          // Our predictions are not useful for e-mail addresses
          // or URIs.
          mPredictionOn = false;
        }

        if ((attribute.inputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
          // If this is an auto-complete text view, then our predictions
          // will not be shown and instead we will allow the editor
          // to supply their own.  We only show the editor's
          // candidates when in fullscreen mode, otherwise relying
          // own it displaying its own UI.
          mPredictionOn = false;
          mCompletionOn = false;
          //mCompletionOn = isFullscreenMode();
        }

        // We also want to look at the current state of the editor
        // to decide whether our alphabetic keyboard should start out
        // shifted.
        updateShiftKeyState(attribute);
        break;

      default:
        // For all unknown input types, default to the alphabetic
        // keyboard with no special features.
        mCurKeyboard = mQwertyKeyboard;
        updateShiftKeyState(attribute);
    }

    // Update the label on the enter key, depending on what the application
    // says it will do.
    mCurKeyboard.setImeOptions(getResources(), attribute.imeOptions);
  }

  @Override
  public void onFinishInputView (boolean finishingInput) {
    super.onFinishInputView(finishingInput);
    sUiDelegate.setApplicationContextAndView(null, null);
    dismissPowerVolumeUi();
    savePreferences();
    stopInventory("onFinishInputView", null);
  }

  /**
   * This is called when the user is done editing a field.  We can use
   * this to reset our state.
   */
  @Override public void onFinishInput() {
    super.onFinishInput();

    // Clear current composing text and candidates.
    mComposing.setLength(0);
    updateCandidates();

    // We only hide the candidates window when finishing input on
    // a particular editor, to avoid popping the underlying application
    // up and down if the user is entering text into the bottom of
    // its window.
    setCandidatesViewShown(false);

    mCurKeyboard = mQwertyKeyboard;
    if (mInputView != null) {
      mInputView.closing();
    }
    stopInventory("onFinishInput", null);
  }

  /////////////////////////////////////////////////////////////////

  @Override
  public void onWindowShown () {
    super.onWindowShown();
    getUgi().activityOnResume(this.mUgiActivity);
    Log.i(TAG, "onWindowShown called!");
  }

  @Override
  public void onWindowHidden () {
    super.onWindowHidden();
    getUgi().activityOnPause(mUgiActivity);
    Log.i(TAG, "onWindowHidden called!");
  }

  /////////////////////////////////////////////////////////////////

  private void stopInventory(final String source,
                             final UgiInventory.StopInventoryCompletion afterInventoryStopped) {
    if (this.mStoppingInventory) {
      Log.i(TAG, "stopInventory called by " + source + " -- mStoppingInventory is TRUE");
    } else {
      UgiInventory inventory = getUgi().getActiveInventory();
      if (inventory != null) {
        Log.i(TAG, "stopInventory called by " + source);
        this.mStoppingInventory = true;
        updateGrokkerIcon();
        inventory.stopInventory(new UgiInventory.StopInventoryCompletion() {
          @Override
          public void exec() {
            SoftKeyboard.this.mStoppingInventory = false;
            SoftKeyboard.this.updateGrokkerIcon();
            Log.i(TAG, "stopInventory called by " + source + " completed");
            if (afterInventoryStopped != null) afterInventoryStopped.exec();
          }
        });
      } else {
        Log.i(TAG, "stopInventory called by " + source + " -- no active inventory");
        if (afterInventoryStopped != null) afterInventoryStopped.exec();
      }
    }
  }

  @Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
    super.onStartInputView(attribute, restarting);
    // Apply the selected keyboard to the input view.
    mInputView.setKeyboard(mCurKeyboard);
    mInputView.closing();
    final InputMethodSubtype subtype = mInputMethodManager.getCurrentInputMethodSubtype();
    mInputView.setSubtypeOnSpaceKey(subtype);

    sUiDelegate.setApplicationContextAndView(this, mInputView);

    updateGrokkerIcon();

    Log.i(TAG, String.format("onStartInputView %sfinished.", restarting ? "(RESTARTING) " : ""));
  }


  private void updateGrokkerIcon () {
    if (this.mInputView == null) {
      Log.w(TAG,"Can't set grok icon: no view yet.");
      return;
    }
    int iconId;
    if (this.mStoppingInventory) {
      iconId = R.drawable.icon_stopping_inventory;
    } else if ((this.mPowerVolumeDialog != null) &&
            this.mPowerVolumeDialog.isShowing() &&
            (android.os.Build.VERSION.SDK_INT >= 17)) {
      iconId = R.drawable.icon_settings_displayed;
    } else {
      Ugi ugi = getUgi();
      Ugi.ConnectionStates state = ugi.getConnectionState();
      switch (state) {
        case CONNECTING:
          iconId = R.drawable.icon_connecting;
          break;
        case CONNECTED:
          if (ugi.getActiveInventory() != null) {
            iconId = R.drawable.icon_scanning;
          } else {
            iconId = R.drawable.icon_connected;
          }
          break;
        case NOT_CONNECTED:
        case INCOMPATIBLE_READER:
        default:
          iconId = R.drawable.icon_not_connected;
          break;
      }
    }

    Keyboard current = mInputView.getKeyboard();
    if (current instanceof LatinKeyboard) {
      ((LatinKeyboard)current).setGrokIcon(getResources().getDrawable(iconId));
      mInputView.invalidateAllKeys();
    }
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype) {
    mInputView.setSubtypeOnSpaceKey(subtype);
  }

  /**
   * Deal with the editor reporting movement of its cursor.
   */
  @Override public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                          int newSelStart, int newSelEnd,
                                          int candidatesStart, int candidatesEnd) {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                            candidatesStart, candidatesEnd);

    // If the current selection in the text view changes, we should
    // clear whatever candidate text we have.
    if (mComposing.length() > 0 && (newSelStart != candidatesEnd
                                    || newSelEnd != candidatesEnd)) {
      mComposing.setLength(0);
      updateCandidates();
      InputConnection ic = getCurrentInputConnection();
      if (ic != null) {
        ic.finishComposingText();
      }
    }
  }

  /**
   * This tells us about completions that the editor has determined based
   * on the current text in it.  We want to use this in fullscreen mode
   * to show the completions ourself, since the editor can not be seen
   * in that situation.
   */
  @Override public void onDisplayCompletions(CompletionInfo[] completions) {
    if (mCompletionOn) {
      mCompletions = completions;
      if (completions == null) {
        setSuggestions(null, false, false);
        return;
      }

      List<String> stringList = new ArrayList<>();
      for (CompletionInfo ci : completions) {
        if (ci != null) stringList.add(ci.getText().toString());
      }
      setSuggestions(stringList, true, true);
    }
  }

  /**
   * This translates incoming hard key events in to edit operations on an
   * InputConnection.  It is only needed when using the
   * PROCESS_HARD_KEYS option.
   */
  private boolean translateKeyDown(int keyCode, KeyEvent event) {
    mMetaState = MetaKeyKeyListener.handleKeyDown(mMetaState,
                                                  keyCode, event);
    int c = event.getUnicodeChar(MetaKeyKeyListener.getMetaState(mMetaState));
    mMetaState = MetaKeyKeyListener.adjustMetaAfterKeypress(mMetaState);
    InputConnection ic = getCurrentInputConnection();
    if (c == 0 || ic == null) {
      return false;
    }

    if ((c & KeyCharacterMap.COMBINING_ACCENT) != 0) {
      c = c & KeyCharacterMap.COMBINING_ACCENT_MASK;
    }

    if (mComposing.length() > 0) {
      char accent = mComposing.charAt(mComposing.length() -1 );
      int composed = KeyEvent.getDeadChar(accent, c);

      if (composed != 0) {
        c = composed;
        mComposing.setLength(mComposing.length()-1);
      }
    }

    onKey(c, null);

    return true;
  }

  /**
   * Use this to monitor key events being delivered to the application.
   * We get first crack at them, and can either resume them or let them
   * continue to the app.
   */
//        case KeyEvent.KEYCODE_ENTER:
//            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
//        mInputView.setShifted(true);
//        keyDownUp(KeyEvent.KEYCODE_ENTER);
//        return false;
  @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
      switch (keyCode) {
          case KeyEvent.KEYCODE_BACK:
              // The InputMethodService already takes care of the back
              // key for us, to dismiss the input method if it is shown.
              // However, our keyboard could be showing a pop-up window
              // that back should dismiss, so we first allow it to do that.
              if (event.getRepeatCount() == 0 && mInputView != null) {
                  if (mInputView.handleBack()) {
                      return true;
                  }
              }
              break;

          case KeyEvent.KEYCODE_DEL:
              // Special handling of the delete key: if we currently are
              // composing text for the user, we want to modify that instead
              // of let the application to the delete itself.
              if (mComposing.length() > 0) {
                  onKey(Keyboard.KEYCODE_DELETE, null);
                  return true;
              }
              break;

//          case KeyEvent.KEYCODE_ENTER:
//              // Let the underlying text editor always handle these.
//            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
//            mInputView.setShifted(true);
//
//
//              return false;

          default:
              // For all other keys, if we want to do transformations on
              // text being entered with a hard keyboard, we need to process
              // it and do the appropriate action.
              if (PROCESS_HARD_KEYS) {
                  if (keyCode == KeyEvent.KEYCODE_SPACE
                          && (event.getMetaState()&KeyEvent.META_ALT_ON) != 0) {
                      // A silly example: in our input method, Alt+Space
                      // is a shortcut for 'android' in lower case.
                      InputConnection ic = getCurrentInputConnection();
                      if (ic != null) {
                          // First, tell the editor that it is no longer in the
                          // shift state, since we are consuming this.
                          ic.clearMetaKeyStates(KeyEvent.META_ALT_ON);
                          keyDownUp(KeyEvent.KEYCODE_A);
                          keyDownUp(KeyEvent.KEYCODE_N);
                          keyDownUp(KeyEvent.KEYCODE_D);
                          keyDownUp(KeyEvent.KEYCODE_R);
                          keyDownUp(KeyEvent.KEYCODE_O);
                          keyDownUp(KeyEvent.KEYCODE_I);
                          keyDownUp(KeyEvent.KEYCODE_D);
                          // And we consume this event.
                          return true;
                      }
                  }
                  if (mPredictionOn && translateKeyDown(keyCode, event)) {
                      return true;
                  }
              }
      }

      return super.onKeyDown(keyCode, event);
  }

  /**
   * Use this to monitor key events being delivered to the application.
   * We get first crack at them, and can either resume them or let them
   * continue to the app.
   */
  @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
    // If we want to do transformations on text being entered with a hard
    // keyboard, we need to process the up events to update the meta key
    // state we are tracking.
    if (PROCESS_HARD_KEYS) {
      if (mPredictionOn) {
        mMetaState = MetaKeyKeyListener.handleKeyUp(mMetaState,
                                                    keyCode, event);
      }
    }

    return super.onKeyUp(keyCode, event);
  }

  /**
   * Helper function to commit any text being composed in to the editor.
   */
  private void commitTyped(InputConnection inputConnection) {
    if (mComposing.length() > 0) {
      inputConnection.commitText(mComposing, mComposing.length());
      mComposing.setLength(0);
      updateCandidates();
    }
  }

  /**
   * Helper to update the shift state of our keyboard based on the initial
   * editor state.
   */
  private void updateShiftKeyState(EditorInfo attr) {
    InputConnection ic = getCurrentInputConnection();
    if (ic == null) return;
    if (attr != null
        && mInputView != null && mQwertyKeyboard == mInputView.getKeyboard()) {
      int caps = 0;
      EditorInfo ei = getCurrentInputEditorInfo();
      if (ei != null && ei.inputType != InputType.TYPE_NULL) {
        caps = ic.getCursorCapsMode(attr.inputType);
      }
      mInputView.setShifted(mCapsLock || caps != 0);
    }
  }

  /**
   * Helper to determine if a given character code is alphabetic.
   */
  private boolean isAlphabet(int code) {
    return Character.isLetter(code);
  }

  /**
   * Helper to send a key down / key up pair to the current editor.
   */
  private void keyDownUp(int keyEventCode) {
    getCurrentInputConnection().sendKeyEvent(
                                            new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode));
    getCurrentInputConnection().sendKeyEvent(
                                            new KeyEvent(KeyEvent.ACTION_UP, keyEventCode));
  }

  /**
   * Helper to send a character to the editor as raw key events.
   */
  private void sendKey(int keyCode) {
    switch (keyCode) {
      case '\n':
        keyDownUp(KeyEvent.KEYCODE_ENTER);

        break;
      default:
        if (keyCode >= '0' && keyCode <= '9') {
          keyDownUp(keyCode - '0' + KeyEvent.KEYCODE_0);
        } else {
          getCurrentInputConnection().commitText(String.valueOf((char) keyCode), 1);
        }
        break;
    }
  }

  // Implementation of KeyboardViewListener

    public void onKey(int primaryCode, int[] keyCodes) {
        if (isWordSeparator(primaryCode)) {
            // Handle separator
            commitTyped(getCurrentInputConnection());
            sendKey(primaryCode);
            updateShiftKeyState(getCurrentInputEditorInfo());
        } else if (primaryCode == Keyboard.KEYCODE_DELETE) {
            handleBackspace();
        } else if (primaryCode == Keyboard.KEYCODE_SHIFT) {
            handleShift();
        } else if (primaryCode == Keyboard.KEYCODE_CANCEL) {
            handleClose();
            //} else if (primaryCode == LatinKeyboardView.KEYCODE_OPTIONS) {
            // Show a menu or somethin'
        } else if (primaryCode == Keyboard.KEYCODE_MODE_CHANGE
                && mInputView != null) {
            Keyboard current = mInputView.getKeyboard();
            if (current == mSymbolsKeyboard || current == mSymbolsShiftedKeyboard) {
                current = mQwertyKeyboard;
            } else {
                current = mSymbolsKeyboard;
            }
            mInputView.setKeyboard(current);
            if (current == mSymbolsKeyboard) {
                current.setShifted(false);
            }
            commitTyped(getCurrentInputConnection());

            updateGrokkerIcon();
        } else if ( primaryCode == 1000) {
            toggleGrok();
        } else if ( primaryCode == 1001) {
            if (!this.mStoppingInventory) {
                showPowerVolumeUi();
            }
        } else if(primaryCode == 10) {
            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            mInputView.setShifted(true);
        }
        else
        {

            handleCharacter(primaryCode, keyCodes);
            commitTyped(getCurrentInputConnection());
        }
    }


  /**
   * Builds a volume/power dialog box.  On OK, updates any current inventory with the new volume/power settings.
   */
  private AlertDialog buildPowerVolumeDialog () {
    AlertDialog.Builder builder = new AlertDialog.Builder(SoftKeyboard.this);
    LayoutInflater inflater = this.getLayoutInflater();
    View sliderView = inflater.inflate(R.layout.power_volume, null);
    builder.setView(sliderView);

    SeekBar powerBar = sliderView.findViewById(R.id.power_seekbar);
    powerBar.setProgress(this.mPowerValue);
    final TextView powerPercentText = sliderView.findViewById(R.id.power_percent_text);
    updatePowerProgress(powerPercentText, this.mPowerValue);

    powerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged (SeekBar seekBar, int progress, boolean fromUser) {
        mPowerValue = progress;
        updatePowerProgress(powerPercentText, progress);
      }

      @Override
      public void onStartTrackingTouch (SeekBar seekBar) { /*empty*/ }

      @Override
      public void onStopTrackingTouch (SeekBar seekBar) { /*empty*/ }
    });

    SeekBar volumeBar = sliderView.findViewById(R.id.volume_seekbar);
    volumeBar.setProgress(this.mVolumeValue);
    final TextView volumePercentText = sliderView.findViewById(R.id.volume_percent_text);
    updateVolumeProgress(volumePercentText, this.mVolumeValue);

    volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged (SeekBar seekBar, int progress, boolean fromUser) {
        mVolumeValue = progress;
        updateVolumeProgress(volumePercentText, progress);
      }

      @Override
      public void onStartTrackingTouch (SeekBar seekBar) { /*empty*/ }

      @Override
      public void onStopTrackingTouch (SeekBar seekBar) { /*empty*/ }
    });

    ToggleButton findOneToggle = sliderView.findViewById(R.id.findone_toggle);
    findOneToggle.setChecked(this.mFindOne);
    findOneToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        mFindOne = isChecked;
      }
    });

    ToggleButton stayConnectedToggle = sliderView.findViewById(R.id.stayconnected_toggle);
    stayConnectedToggle.setChecked(this.mStayConnected);
    stayConnectedToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        mStayConnected = isChecked;
        getUgi().setDelayBeforeClosingConnectionOnInactivityMsec(mStayConnected ? 0 : 100);
      }
    });

    ToggleButton asciiToggle = sliderView.findViewById(R.id.ascii_toggle);
    asciiToggle.setChecked(this.mAscii);
    asciiToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        mAscii = isChecked;
      }
    });

    ToggleButton alwaysAddCommaAtStartToggle = sliderView.findViewById(R.id.always_add_comma_at_start_toggle);
    alwaysAddCommaAtStartToggle.setChecked(this.mAlwaysAddCommaAtStart);
    alwaysAddCommaAtStartToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        mAlwaysAddCommaAtStart = isChecked;
      }
    });

    String version;
    try {
      version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (PackageManager.NameNotFoundException ex) {
      version = "(unknown)";
    }
    builder.setTitle("Grok Keyboard: " + version);
    builder.setPositiveButton("ok", new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int id) {
        mPowerVolumeDialog.hide();
        SoftKeyboard.this.updateGrokkerIcon();
        SoftKeyboard.this.savePreferences();
      }
    });

    if (android.os.Build.VERSION.SDK_INT >= 17) {
      builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
        @Override
        public void onDismiss(DialogInterface dialog) {
          SoftKeyboard.this.mPowerVolumeDialog = null;
          SoftKeyboard.this.updateGrokkerIcon();
        }
      });
    }

    this.mPowerVolumeDialog = builder.create();
    this.mPowerVolumeDialog.setCanceledOnTouchOutside(false);
    return this.mPowerVolumeDialog;
  }

  public void showPowerVolumeUi() {
    if (this.mPowerVolumeDialog == null) {
      this.mPowerVolumeDialog = buildPowerVolumeDialog();
    }

    sUiDelegate.showDialog(this.mPowerVolumeDialog);

    // Stop any active inventory
    updateGrokkerIcon();
    if (getUgi().getActiveInventory() != null) {
      final Button okButton = this.mPowerVolumeDialog.getButton(DialogInterface.BUTTON_POSITIVE);
      okButton.setEnabled(false);

      //setBatteryPowerToPending(this.mPowerVolumeDialog);

      stopInventory("showPowerVolumeUi", new UgiInventory.StopInventoryCompletion() {
        @Override
        public void exec() {
          SoftKeyboard.this.updateBatteryPower(mPowerVolumeDialog);
          okButton.setEnabled(true);
        }
      });
    }

    updateBatteryPower(this.mPowerVolumeDialog);
    updateSliderValues(this.mPowerVolumeDialog);
  }

  private void updateSliderValues (AlertDialog dialog) {
    SeekBar powerBar = dialog.findViewById(R.id.power_seekbar);
    powerBar.setProgress(this.mPowerValue);
    final TextView powerPercentText = dialog.findViewById(R.id.power_percent_text);
    updatePowerProgress(powerPercentText, this.mPowerValue);

    SeekBar volumeBar = dialog.findViewById(R.id.volume_seekbar);
    volumeBar.setProgress(this.mVolumeValue);
    final TextView volumePercentText = dialog.findViewById(R.id.volume_percent_text);
    updateVolumeProgress(volumePercentText, this.mVolumeValue);
  }

  private static void updatePowerProgress(TextView powerPercentText, int progress) {
    // Range: 5% - 100%
    int modifiedRange = (int) ((95.0 * (progress / 100.0)) + 5);
    powerPercentText.setText(String.format("%d%%", modifiedRange));
  }

  private static void updateVolumeProgress(TextView volumePercentText, int progress) {
    volumePercentText.setText(
                             (progress == 0) ? "Off" :
                             String.format("%d%%", progress));
  }



  /*
   * Updates the battery level in the power/volume UI.
   */
  private void setBatteryPowerToPending (AlertDialog dialog) {
    if (dialog == null) {
      return;
    }

    LinearLayout batteryLevelView = dialog.findViewById(R.id.battery_level_layout);
    TextView batteryLevelText = dialog.findViewById(R.id.battery_level_text);

    if (batteryLevelView == null) {
      return;
    }

    batteryLevelText.setText(R.string.battery_level_pending);
    batteryLevelView.setVisibility(View.VISIBLE);
  }


  /*
   * Updates the battery level in the power/volume UI.
   */
  private void updateBatteryPower (final AlertDialog dialog) {
    if (dialog == null) {
      return;
    }
    getUgi().getBatteryInfo(new Ugi.GetBatteryInfoCompletion() {
      @Override
      public void exec(Ugi.BatteryInfo info) {
        LinearLayout batteryLevelView = dialog.findViewById(R.id.battery_level_layout);
        TextView batteryLevelText = dialog.findViewById(R.id.battery_level_text);
        if (batteryLevelView == null) {
          return;
        }
        if (info != null) {
          StringBuilder message = new StringBuilder();
          if (info.externalPowerIsConnected) {
            message.append(info.isCharging
                                   ? SoftKeyboard.this.getString(R.string.battery_charging)
                                   : SoftKeyboard.this.getString(R.string.battery_fully_charged));
          } else {
            message.append(String.format(SoftKeyboard.this.getString(R.string.battery_not_charging_format), info.percentRemaining));
          }
          batteryLevelText.setText(message);
          batteryLevelView.setVisibility(View.VISIBLE);
        } else {
          if (getUgi().isConnected()) {
            SoftKeyboard.this.setBatteryPowerToPending(dialog);
          } else {
            batteryLevelView.setVisibility(View.GONE);
          }
        }
      }
    });
  }

  private void dismissPowerVolumeUi() {
    Log.i(TAG, "dismissPowerVolumeUi");
    if (this.mPowerVolumeDialog != null) {
      this.mPowerVolumeDialog.dismiss();
      this.mPowerVolumeDialog = null;
      updateGrokkerIcon();
    }
  }

  private void toggleGrok () {
    if (this.mStoppingInventory) {
      Log.i(TAG, "Stopping inventory, so ignoring keypress.");
      return;
    }

    // Confusing if we change the icon with no grokker attached.
    if (getUgi().getConnectionState() != Ugi.ConnectionStates.CONNECTED) {
      updateGrokkerIcon();
      return;
    }

    UgiInventory inventory = getUgi().getActiveInventory();

    if (inventory == null) {
      Log.i(TAG, "Starting inventory!");
      this.mFirstEpc = true;
      getUgi().startInventory(this, getRfidConfiguration());
    } else {
      stopInventory("toggleGrok", null);
      return;
    }

    updateGrokkerIcon();
  }

  /**
   * Returns a UgiRfidConfiguration with the volume and power settings scaled by
   * mVolumeValue and mPowerValue.  Uses INVENTORY_DISTANCE for the base (100%) values.
   */
  private UgiRfidConfiguration getRfidConfiguration() {
    UgiRfidConfiguration config =
            UgiRfidConfiguration.forInventoryType(this.mFindOne
                                                          ? UgiRfidConfiguration.InventoryTypes.SINGLE_FIND
                                                          : UgiRfidConfiguration.InventoryTypes.INVENTORY_DISTANCE);
    config.volume = (double) this.mVolumeValue / 100.0;

    // power conversion: scale initial, min, and max power by the scale factor
    double minPower = UgiRfidConfiguration.getMinAllowablePowerLevel();
    double powerScale = ((double) this.mPowerValue / 100.0d);
    config.initialPowerLevel = minPower + ((config.initialPowerLevel - minPower) * powerScale);
    config.minPowerLevel = minPower + ((config.minPowerLevel - minPower) * powerScale);
    config.maxPowerLevel = minPower + ((config.maxPowerLevel - minPower) * powerScale);
    return config;
  }

  public void onText(CharSequence text) {
    InputConnection ic = getCurrentInputConnection();
    if (ic == null) return;
    ic.beginBatchEdit();
    commitTyped(ic);
    ic.commitText(text, text.length());
    ic.endBatchEdit();
    updateShiftKeyState(getCurrentInputEditorInfo());
  }

  /**
   * Update the list of available candidates from the current composing
   * text.  This will need to be filled in by however you are determining
   * candidates.
   */
  private void updateCandidates() {
    if (mCompletionOn) {
      if (mComposing.length() > 0) {
        ArrayList<String> list = new ArrayList<>();
        list.add(mComposing.toString());
        setSuggestions(list, true, true);
      } else {
        setSuggestions(null, false, false);
      }
    }
  }

  public void setSuggestions(List<String> suggestions, boolean completions,
                             boolean typedWordValid) {
    if (suggestions != null && suggestions.size() > 0) {
      setCandidatesViewShown(true);
    } else if (isExtractViewShown()) {
      setCandidatesViewShown(true);
    }
    if (mCandidateView != null) {
      mCandidateView.setSuggestions(suggestions, completions, typedWordValid);
    }
  }

  private void handleBackspace() {
    final int length = mComposing.length();
    if (length > 1) {
      mComposing.delete(length - 1, length);
      getCurrentInputConnection().setComposingText(mComposing, 1);
      updateCandidates();
    } else if (length > 0) {
      mComposing.setLength(0);
      getCurrentInputConnection().commitText("", 0);
      updateCandidates();
    } else {
      keyDownUp(KeyEvent.KEYCODE_DEL);
    }
    updateShiftKeyState(getCurrentInputEditorInfo());
  }

  private void handleShift() {
    if (mInputView == null) {
      return;
    }

    Keyboard currentKeyboard = mInputView.getKeyboard();
    if (mQwertyKeyboard == currentKeyboard) {
      // Alphabet keyboard
      checkToggleCapsLock();
      mInputView.setShifted(mCapsLock || !mInputView.isShifted());
    } else if (currentKeyboard == mSymbolsKeyboard) {
      mSymbolsKeyboard.setShifted(true);
      mInputView.setKeyboard(mSymbolsShiftedKeyboard);
      updateGrokkerIcon();
      mSymbolsShiftedKeyboard.setShifted(true);
    } else if (currentKeyboard == mSymbolsShiftedKeyboard) {
      mSymbolsShiftedKeyboard.setShifted(false);
      mInputView.setKeyboard(mSymbolsKeyboard);
      updateGrokkerIcon();
      mSymbolsKeyboard.setShifted(false);
    }
  }

  private void handleCharacter(int primaryCode, int[] keyCodes) {
    if (isInputViewShown()) {
      if (mInputView.isShifted()) {
        primaryCode = Character.toUpperCase(primaryCode);
      }
    }
    if (isAlphabet(primaryCode) && (mPredictionOn || mHandleShift)) {
      mComposing.append((char) primaryCode);
      //removed because of composing text without prediction
      getCurrentInputConnection().setComposingText(mComposing, 1);
      updateShiftKeyState(getCurrentInputEditorInfo());
      //added to remove composing text
      //getCurrentInputConnection().commitText(String.valueOf((char) primaryCode), 1);
      if (mPredictionOn) {
        updateCandidates();
      }
    } else {
      getCurrentInputConnection().commitText(
                                            String.valueOf((char) primaryCode), 1);
    }
  }

  private void handleClose() {
    commitTyped(getCurrentInputConnection());
    requestHideSelf(0);
    mInputView.closing();
  }

  private void checkToggleCapsLock() {
    long now = System.currentTimeMillis();
    if (mLastShiftTime + 800 > now) {
      mCapsLock = !mCapsLock;
      mLastShiftTime = 0;
    } else {
      mLastShiftTime = now;
    }
  }

  private String getWordSeparators() {
    return mWordSeparators;
  }

  public boolean isWordSeparator(int code) {
    String separators = getWordSeparators();
    return separators.contains(String.valueOf((char)code));
  }

  public void pickDefaultCandidate() {
    pickSuggestionManually(0);
  }

  public void pickSuggestionManually(int index) {
    if (mCompletionOn && mCompletions != null && index >= 0
        && index < mCompletions.length) {
      CompletionInfo ci = mCompletions[index];
      getCurrentInputConnection().commitCompletion(ci);
      if (mCandidateView != null) {
        mCandidateView.clear();
      }
      updateShiftKeyState(getCurrentInputEditorInfo());
    } else if (mComposing.length() > 0) {
      // If we were generating candidate suggestions for the current
      // text, we would commit one of them here.  But for this sample,
      // we will just commit the current text.
      commitTyped(getCurrentInputConnection());
    }
  }

  public void swipeRight() {
    if (mCompletionOn) {
      pickDefaultCandidate();
    }
  }

  public void swipeLeft() {
    handleBackspace();
  }

  public void swipeDown() {
    handleClose();
  }

  public void swipeUp() {
  }

  public void onPress(int primaryCode) {
  }

  public void onRelease(int primaryCode) {
  }

  @Override
  public void connectionStateChanged (Ugi.ConnectionStates connectionState) {
    Log.i(TAG, "Ugi connectionState changed: " + connectionState);
    updateGrokkerIcon();
  }

  @Override
  public void inventoryTagFound (UgiTag tag, UgiInventory.DetailedPerReadData[] detailedPerReadData) {
    Log.i(TAG, "Tag found: " + tag.toString());
    StringBuilder sb = new StringBuilder();

    if (this.mFindOne) {
      if (this.mFirstEpc) {
        this.mFirstEpc = false;
        stopInventory("inventoryTagFound", null);
      } else {
        return;
      }
    } else {
      if (this.mFirstEpc) {
        this.mFirstEpc = false;
      }
      //
      // Finding more than one EPC, if there is text to the left
      // then insert a comma as a seperator
      //
      if (mAlwaysAddCommaAtStart) {
        sb.append(',');
      } else {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
          CharSequence q = ic.getTextBeforeCursor(1, 0);
          if (q.length() > 0) {
            sb.append(',');
          }
        }
      }
    }
    sb.append(epcToDisplayString(tag.getEpc()));

    onText(sb.toString());
  }

  public String epcToDisplayString(UgiEpc epc) {
    if (epc == null) return "";
    String epcString = epc.toString();
    if (mAscii) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < epcString.length(); i += 2) {
        String s = epcString.substring(i, i + 2);
        int ch = Integer.parseInt(s, 16);
        if (ch == 0) {
          break;
        }
        if (ch >= 128) {
          return epcString;
        }
        sb.append((char) ch);
      }
      return sb.length() > 0 ? sb.toString() : epcString;
    } else {
      return epcString.toUpperCase();
    }
  }

  @Override public void inventoryDidStop(int result) {
    if (result == UGI_INVENTORY_COMPLETED_LOST_CONNECTION) {
      return;
    }

    if (result != UGI_INVENTORY_COMPLETED_OK) {
      updateGrokkerIcon();

      String message;
      if (result == UGI_INVENTORY_COMPLETED_BATTERY_TOO_LOW) {
        message = "Battery level too low\nPlease charge the Grokker";
      } else if (result == UGI_INVENTORY_COMPLETED_TEMPERATURE_TOO_HIGH) {
        message = "Temperature too high\nAllow the Grokker to cool down";
      } else if (result == UGI_INVENTORY_COMPLETED_NOT_PROVISIONED) {
        message = "Grokker is not provisioned";
      } else if (result == UGI_INVENTORY_COMPLETED_REGION_NOT_SET) {
        message = "Region is not set";
      } else if (result == UGI_INVENTORY_COMPLETED_ERROR_SENDING) {
        message = "Error communicating with Grokker";
      } else {
        message = "Grokker error: " + result;
      }

      UgiUiUtil.showOk(mUgiActivity, "Inventory Error", message, "", null);
    }
  }

}
