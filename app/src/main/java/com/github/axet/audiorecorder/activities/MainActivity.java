package com.github.axet.audiorecorder.activities;

import android.app.KeyguardManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.axet.androidlibrary.activities.AppCompatThemeActivity;
import com.github.axet.androidlibrary.preferences.AboutPreferenceCompat;
import com.github.axet.androidlibrary.preferences.OptimizationPreferenceCompat;
import com.github.axet.androidlibrary.services.StorageProvider;
import com.github.axet.androidlibrary.widgets.ErrorDialog;
import com.github.axet.androidlibrary.widgets.OpenFileDialog;
import com.github.axet.androidlibrary.widgets.SearchView;
import com.github.axet.audiolibrary.app.RawSamples;
import com.github.axet.audiolibrary.encoders.FormatWAV;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.app.AudioApplication;
import com.github.axet.audiorecorder.app.Recordings;
import com.github.axet.audiorecorder.app.Storage;
import com.github.axet.audiorecorder.services.EncodingService;
import com.github.axet.audiorecorder.services.RecordingService;

import org.json.JSONException;

import java.io.File;

public class MainActivity extends AppCompatThemeActivity {
    public final static String TAG = MainActivity.class.getSimpleName();

    public static final int RESULT_PERMS = 1;

    FloatingActionButton fab;

    RecyclerView list;
    Recordings recordings;
    Storage storage;

    ScreenReceiver receiver;
    EncodingDialog encoding;

    public static void startActivity(Context context) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(i);
    }

    public static class SpeedInfo extends com.github.axet.wget.SpeedInfo {
        public Sample getLast() {
            if (samples.size() == 0)
                return null;
            return samples.get(samples.size() - 1);
        }

        public long getDuration() { // get duration of last segment [start,last]
            if (start == null || getRowSamples() < 2)
                return 0;
            return getLast().now - start.now;
        }
    }

    public static class ProgressEncoding extends ProgressDialog {
        public static int DURATION = 5000;

        public long pause;
        public long resume;
        public long samplesPause; // encoding progress on pause
        public long samplesResume; // encoding progress on resume
        SpeedInfo current;
        SpeedInfo foreground;
        SpeedInfo background;
        LinearLayout view;
        View speed;
        TextView text;
        View warning;
        RawSamples.Info info;

        public ProgressEncoding(Context context, RawSamples.Info info) {
            super(context);
            setMax(100);
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            setIndeterminate(false);
            this.info = info;
        }

        @Override
        public void setView(View v) {
            view = new LinearLayout(getContext());
            view.setOrientation(LinearLayout.VERTICAL);
            super.setView(view);
            view.addView(v);
            LayoutInflater inflater = LayoutInflater.from(getContext());
            speed = inflater.inflate(R.layout.encoding_speed, view);
            text = (TextView) speed.findViewById(R.id.speed);
        }

        public void onPause(long cur) {
            pause = System.currentTimeMillis();
            samplesPause = cur;
            resume = 0;
            samplesResume = 0;
            if (background == null)
                background = new SpeedInfo();
            background.start(cur);
        }

        public void onResume(long cur) {
            resume = System.currentTimeMillis();
            samplesResume = cur;
            if (foreground == null)
                foreground = new SpeedInfo();
            foreground.start(cur);
        }

        public void setProgress(long cur, long total) {
            if (current == null) {
                current = new SpeedInfo();
                current.start(cur);
            } else {
                current.step(cur);
            }
            if (pause == 0 && resume == 0) { // foreground
                if (foreground == null) {
                    foreground = new SpeedInfo();
                    foreground.start(cur);
                } else {
                    foreground.step(cur);
                }
            }
            if (pause != 0 && resume == 0) // background
                background.step(cur);
            if (pause != 0 && resume != 0) { // resumed from background
                long diffreal = resume - pause; // real time
                long diffenc = (samplesResume - samplesPause) * 1000 / info.hz / info.channels; // encoding time
                if (diffreal > 0 && diffenc < diffreal && warning == null) { // paused
                    LayoutInflater inflater = LayoutInflater.from(getContext());
                    warning = inflater.inflate(R.layout.optimization, view);
                }
                if (diffreal > 0 && diffenc >= diffreal && warning == null && foreground != null && background != null) {
                    if (foreground.getDuration() > DURATION && background.getDuration() > DURATION) {
                        long r = foreground.getAverageSpeed() / background.getAverageSpeed();
                        if (r > 1) { // slowed down by twice or more
                            LayoutInflater inflater = LayoutInflater.from(getContext());
                            warning = inflater.inflate(R.layout.slow, view);
                        }
                    }
                }
            }
            text.setText(AudioApplication.formatSize(getContext(), current.getAverageSpeed() * info.bps / Byte.SIZE) + getContext().getString(R.string.per_second));
            super.setProgress((int) (cur * 100 / total));
        }
    }

    public class EncodingDialog extends BroadcastReceiver {
        Context context;
        Snackbar snackbar;
        IntentFilter filter = new IntentFilter();
        ProgressEncoding d;
        long cur;
        long total;

        public EncodingDialog() {
            filter.addAction(EncodingService.UPDATE_ENCODING);
            filter.addAction(EncodingService.ERROR);
        }

        public void registerReceiver(Context context) {
            this.context = context;
            context.registerReceiver(this, filter);
        }

        public void close() {
            context.unregisterReceiver(this);
        }

        @Override
        public void onReceive(final Context context, Intent intent) {
            String a = intent.getAction();
            if (a == null)
                return;
            if (a.equals(EncodingService.UPDATE_ENCODING)) {
                cur = intent.getLongExtra("cur", -1);
                total = intent.getLongExtra("total", -1);
                final long progress = cur * 100 / total;
                boolean done = intent.getBooleanExtra("done", false);
                final Uri targetUri = intent.getParcelableExtra("targetUri");
                final RawSamples.Info info;
                try {
                    String j = intent.getStringExtra("info");
                    if (j != null)
                        info = new RawSamples.Info(j);
                    else
                        info = null;
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }

                String p = " (" + progress + "%)";

                if (d != null)
                    d.setProgress(cur, total);

                EncodingService.EncodingStorage storage = new EncodingService.EncodingStorage(new Storage(context));
                String str = "";
                for (File f : storage.keySet()) {
                    EncodingService.EncodingStorage.Info n = storage.get(f);
                    String name = Storage.getName(context, n.targetUri);
                    str += "- " + name;
                    if (n.targetUri.equals(targetUri))
                        str += p;
                    str += "\n";
                }

                str = str.trim();

                if (snackbar == null || !snackbar.isShown()) {
                    snackbar = Snackbar.make(fab, str, Snackbar.LENGTH_LONG);
                    snackbar.setDuration(Snackbar.LENGTH_INDEFINITE);
                    snackbar.getView().setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            d = new ProgressEncoding(context, info);
                            d.setTitle(R.string.encoding_title);
                            d.setMessage(".../" + Storage.getName(context, targetUri));
                            d.show();
                            d.setProgress(cur, total);
                            EncodingService.startIfPending(context);
                        }
                    });
                    snackbar.show();
                } else {
                    snackbar.setDuration(Snackbar.LENGTH_INDEFINITE);
                    snackbar.setText(str);
                    snackbar.show();
                }

                if (done) {
                    recordings.load(false, null);
                    if (snackbar != null && snackbar.isShown()) {
                        snackbar.setDuration(Snackbar.LENGTH_SHORT);
                        snackbar.show();
                    }
                }
            }
            if (a.equals(EncodingService.ERROR)) {
                if (d != null) {
                    d.dismiss();
                    d = null;
                }
                File in = (File) intent.getSerializableExtra("in");
                RawSamples.Info info;
                try {
                    info = new RawSamples.Info(intent.getStringExtra("info"));
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                Throwable e = (Throwable) intent.getSerializableExtra("e");
                Error(in, info, e);
            }
        }

        public void onPause() {
            if (d != null)
                d.onPause(cur);
        }

        public void onResume() {
            if (d != null)
                d.onResume(cur);
        }

        public void Error(final File in, final RawSamples.Info info, Throwable e) {
            ErrorDialog builder = new ErrorDialog(context, ErrorDialog.toMessage(e));
            builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                }
            });
            builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            if (in.length() > 0) {
                builder.setNeutralButton(R.string.save_as_wav, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final OpenFileDialog d = new OpenFileDialog(context, OpenFileDialog.DIALOG_TYPE.FOLDER_DIALOG);
                        d.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                EncodingService.saveAsWAV(context, in, d.getCurrentPath(), info);
                            }
                        });
                        d.show();
                    }
                });
            }
            builder.show();
        }

        public void hide() {
            snackbar.dismiss();
        }
    }

    @Override
    public int getAppTheme() {
        return AudioApplication.getTheme(this, R.style.RecThemeLight_NoActionBar, R.style.RecThemeDark_NoActionBar);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_main);
        final View content = findViewById(android.R.id.content);

        storage = new Storage(this);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                recordings.select(-1);
                finish();
                RecordingActivity.startActivity(MainActivity.this, false);
            }
        });


        list = (RecyclerView) findViewById(R.id.list);
        recordings = new Recordings(this, list) {
            @Override
            public boolean getPrefCall() {
                final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
                return shared.getBoolean(AudioApplication.PREFERENCE_CALL, false);
            }

            @Override
            public void showDialog(AlertDialog.Builder e) {
                AlertDialog d = e.create();
                showDialogLocked(d.getWindow());
                d.show();
            }
        };
        recordings.setEmptyView(findViewById(R.id.empty_list));
        list.setAdapter(recordings.empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recordings.setToolbar((ViewGroup) findViewById(R.id.recording_toolbar));

        receiver = new ScreenReceiver() {
            @Override
            public void onScreenOff() {
                boolean p = storage.recordingPending();
                boolean c = shared.getBoolean(AudioApplication.PREFERENCE_CONTROLS, false);
                if (!p && !c)
                    return;
                super.onScreenOff();
            }
        };
        receiver.registerReceiver(this);
        encoding = new EncodingDialog();
        encoding.registerReceiver(this);

        RecordingService.startIfPending(this);
        EncodingService.startIfPending(this);

        try {
            new Recordings.ExoLoader(this, false);
        } catch (Exception e) {
            Log.e(TAG, "error", e);
        }
    }

    void checkPending() {
        if (storage.recordingPending()) {
            finish();
            RecordingActivity.startActivity(MainActivity.this, true);
            return;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.menu_main, menu);

        KeyguardManager myKM = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (myKM.inKeyguardRestrictedInputMode())
            menu.findItem(R.id.action_settings).setVisible(false);

        MenuItem item = menu.findItem(R.id.action_show_folder);
        Intent intent = StorageProvider.openFolderIntent(this, storage.getStoragePath());
        item.setIntent(intent);
        if (!StorageProvider.isFolderCallable(this, intent, StorageProvider.getProvider().getAuthority()))
            item.setVisible(false);

        MenuItem search = menu.findItem(R.id.action_search);
        final SearchView searchView = (SearchView) MenuItemCompat.getActionView(search);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchView.clearFocus();
                recordings.search(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });
        searchView.setOnCloseListener(new SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
                recordings.searchClose();
                return true;
            }
        });

        recordings.onCreateOptionsMenu(menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (recordings.onOptionsItemSelected(this, item))
            return true;

        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case R.id.action_about:
                AboutPreferenceCompat.showDialog(this, R.raw.about);
                return true;
            case R.id.action_show_folder:
                Intent intent = item.getIntent();
                startActivity(intent);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        encoding.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");

        encoding.onResume();

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        invalidateOptionsMenu(); // update storage folder intent

        try {
            storage.migrateLocalStorage();
        } catch (Exception e) {
            ErrorDialog.Error(this, e);
        }

        final String last = shared.getString(AudioApplication.PREFERENCE_LAST, "");
        Runnable done = new Runnable() {
            @Override
            public void run() {
                final int selected = getLastRecording(last);
                recordings.progressEmpty.setVisibility(View.GONE);
                recordings.progressText.setVisibility(View.VISIBLE);
                if (selected != -1) {
                    recordings.select(selected);
                    list.smoothScrollToPosition(selected);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            list.scrollToPosition(selected);
                        }
                    });
                }
            }
        };
        recordings.progressEmpty.setVisibility(View.VISIBLE);
        recordings.progressText.setVisibility(View.GONE);

        recordings.load(!last.isEmpty(), done);

        if (OptimizationPreferenceCompat.needKillWarning(this, AudioApplication.PREFERENCE_NEXT)) {
            AlertDialog.Builder muted;
            if (Build.VERSION.SDK_INT >= 28)
                muted = new ErrorDialog(this, getString(R.string.optimization_killed) + "\n\n" + getString(R.string.mic_muted_pie)).setTitle("Error");
            else
                muted = new ErrorDialog(this, getString(R.string.optimization_killed)).setTitle("Error");
            muted.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    checkPending();
                }
            });
            muted.show();
        } else {
            checkPending();
        }

        updateHeader();
    }

    int getLastRecording(String last) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        for (int i = 0; i < recordings.getItemCount(); i++) {
            Storage.RecordingUri f = recordings.getItem(i);
            if (f.name.equals(last)) {
                SharedPreferences.Editor edit = shared.edit();
                edit.putString(AudioApplication.PREFERENCE_LAST, "");
                edit.commit();
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case RESULT_PERMS:
                if (Storage.permitted(MainActivity.this, permissions)) {
                    try {
                        storage.migrateLocalStorage();
                    } catch (RuntimeException e) {
                        ErrorDialog.Error(MainActivity.this, e);
                    }
                    recordings.load(false, null);
                    checkPending();
                } else {
                    Toast.makeText(this, R.string.not_permitted, Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recordings.close();
        receiver.close();
        encoding.close();
    }

    void updateHeader() {
        Uri uri = storage.getStoragePath();
        long free = Storage.getFree(this, uri);
        long sec = Storage.average(this, free);
        TextView text = (TextView) findViewById(R.id.space_left);
        text.setText(AudioApplication.formatFree(this, free, sec));
    }
}
