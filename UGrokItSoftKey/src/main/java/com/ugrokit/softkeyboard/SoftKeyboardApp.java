package com.ugrokit.softkeyboard;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.util.Log;
import com.ugrokit.api.Ugi;
import com.ugrokit.api.UgiServer;

/**
 * Android Sot keyboard app
 */
public class SoftKeyboardApp extends Application {

  private static final String TAG = "SoftKeyboardApp";
  private static SoftKeyboardApp sApplication;

  private static class Singleton {
    private Ugi mUgi;
    private Context mUiContext;
  }

  @SuppressLint("StaticFieldLeak")
  private static Singleton sSingleton = null;

  @Override public void onCreate() {
    super.onCreate();
    sApplication = this;
  }


  public static synchronized Ugi getUgi() {
    if (sSingleton == null) {
      Log.e(TAG, "getUgi: singleton not created!");
      throw new IllegalStateException("ugi singleton has not been created; slap the programmer.");
    }

    return sSingleton.mUgi;
  }


  public static synchronized void setUiContext(SoftKeyboard context) {
    if (sSingleton == null) {
      sSingleton = new Singleton();
      sSingleton.mUgi = Ugi.createSingleton(sApplication);
      UgiServer.getSingleton().setDebugLevel(UgiServer.DebugLevels.OneLine);

      Log.i(TAG, "Initialized UgiSingleton");
    }

    // In case we're called multiple times.
    sSingleton.mUiContext = context;
  }


  public static Context getUiContext() {
    if (sSingleton == null) {
      Log.e(TAG, "getContext: singleton not created!");
      throw new IllegalStateException("ugi singleton has not been created; slap the programmer.");
    }

    return sSingleton.mUiContext;
  }
}
