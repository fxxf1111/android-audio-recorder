package com.github.axet.audiorecorder.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;

import com.github.axet.androidlibrary.app.AlarmManager;
import com.github.axet.androidlibrary.app.ProximityShader;
import com.github.axet.androidlibrary.preferences.OptimizationPreferenceCompat;
import com.github.axet.androidlibrary.services.PersistentService;
import com.github.axet.androidlibrary.widgets.RemoteNotificationCompat;
import com.github.axet.audiolibrary.app.RawSamples;
import com.github.axet.audiolibrary.encoders.FileEncoder;
import com.github.axet.audiolibrary.encoders.FormatWAV;
import com.github.axet.audiolibrary.encoders.OnFlyEncoding;
import com.github.axet.audiolibrary.filters.AmplifierFilter;
import com.github.axet.audiolibrary.filters.SkipSilenceFilter;
import com.github.axet.audiolibrary.filters.VoiceFilter;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.activities.MainActivity;
import com.github.axet.audiorecorder.activities.RecordingActivity;
import com.github.axet.audiorecorder.app.AudioApplication;
import com.github.axet.audiorecorder.app.Storage;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.charset.Charset;
import java.util.HashMap;

public class EncodingService extends PersistentService {
    public static final String TAG = EncodingService.class.getSimpleName();

    public static final int NOTIFICATION_RECORDING_ICON = 2;

    public static String SHOW_ACTIVITY = EncodingService.class.getCanonicalName() + ".SHOW_ACTIVITY";
    public static String SAVE_AS_WAV = EncodingService.class.getCanonicalName() + ".SAVE_AS_WAV";
    public static String UPDATE_ENCODING = EncodingService.class.getCanonicalName() + ".UPDATE_ENCODING";
    public static String DONE_ENCODING = EncodingService.class.getCanonicalName() + ".DONE_ENCODING";
    public static String START_ENCODING = EncodingService.class.getCanonicalName() + ".START_ENCODING";
    public static String ERROR = EncodingService.class.getCanonicalName() + ".ERROR";

    public static String JSON_EXT = "json";

    static {
        OptimizationPreferenceCompat.REFRESH = AlarmManager.MIN1;
    }

    Storage storage; // for storage path
    EncodingStorage encodings;
    FileEncoder encoder;

    public static void startIfPending(Context context) { // if encoding pending
        Storage storage = new Storage(context);
        EncodingStorage enc = new EncodingStorage(storage);
        if (!enc.isEmpty()) {
            start(context);
            return;
        }
    }

    public static void start(Context context) { // start persistent icon service
        start(context, new Intent(context, EncodingService.class));
    }

    public static void saveAsWAV(Context context, File in, File out, RawSamples.Info info) { // start encoding process for selected file
        try {
            start(context, new Intent(context, EncodingService.class).setAction(SAVE_AS_WAV)
                    .putExtra("in", in)
                    .putExtra("out", out)
                    .putExtra("info", info.save().toString())
            );
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public static void stop(Context context) {
        stop(context, new Intent(context, EncodingService.class));
    }

    public void Error(File in, RawSamples.Info info, Throwable e) {
        try {
            sendBroadcast(new Intent(ERROR)
                    .putExtra("in", in)
                    .putExtra("info", info.save().toString())
                    .putExtra("e", e)
            );
        } catch (JSONException e1) {
            throw new RuntimeException(e1);
        }
    }

    public static void startEncoding(Context context, File in, Uri targetUri, RawSamples.Info info) {
        try {
            EncodingStorage storage = new EncodingStorage(new Storage(context));
            in = storage.save(in, targetUri, info);
            start(context, new Intent(context, EncodingService.class).setAction(START_ENCODING)
                    .putExtra("in", in)
                    .putExtra("targetUri", targetUri)
                    .putExtra("info", info.save().toString())
            );
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public static class EncodingStorage extends HashMap<File, EncodingStorage.Info> {
        public Storage storage;

        public static File jsonFile(File f) {
            return new File(f.getParentFile(), Storage.getNameNoExt(f) + "." + JSON_EXT);
        }

        public static class Info {
            public Uri targetUri;
            public RawSamples.Info info;

            public Info() {
            }

            public Info(Uri t, RawSamples.Info i) {
                this.targetUri = t;
                this.info = i;
            }

            public Info(String json) throws JSONException {
                load(new JSONObject(json));
            }

            public Info(JSONObject json) throws JSONException {
                load(json);
            }

            public JSONObject save() throws JSONException {
                JSONObject json = new JSONObject();
                json.put("targetUri", targetUri.toString());
                json.put("info", info.save());
                return json;
            }

            public void load(JSONObject json) throws JSONException {
                targetUri = Uri.parse(json.getString("targetUri"));
                info = new RawSamples.Info(json.getJSONObject("info"));
            }
        }

        public EncodingStorage(Storage s) {
            storage = s;
            load();
        }

        public void load() {
            clear();
            File storage = this.storage.getTempRecording().getParentFile();
            File[] ff = storage.listFiles(new FilenameFilter() {
                String start = Storage.getNameNoExt(Storage.TMP_ENC);
                String ext = Storage.getExt(Storage.TMP_ENC);

                @Override
                public boolean accept(File dir, String name) {
                    return name.startsWith(start) && name.endsWith("." + ext);
                }
            });
            if (ff == null)
                return;
            for (File f : ff) {
                File j = jsonFile(f);
                try {
                    put(f, new Info(new JSONObject(FileUtils.readFileToString(j, Charset.defaultCharset()))));
                } catch (Exception e) {
                    Log.d(TAG, "unable to read json", e);
                }
            }
        }

        public File save(File in, Uri targetUri, RawSamples.Info info) {
            File to = storage.getTempEncoding();
            to = Storage.getNextFile(to);
            to = Storage.move(in, to);
            try {
                File j = jsonFile(to);
                Info rec = new Info(targetUri, info);
                JSONObject json = rec.save();
                FileUtils.writeStringToFile(j, json.toString(), Charset.defaultCharset());
                return to;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public EncodingService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onCreateOptimization() {
        storage = new Storage(this);
        encodings = new EncodingStorage(storage);
        optimization = new OptimizationPreferenceCompat.ServiceReceiver(this, NOTIFICATION_RECORDING_ICON, null, AudioApplication.PREFERENCE_NEXT) {
            Intent notificationIntent;

            @Override
            public void onCreateIcon(Service service, int id) {
                icon = new OptimizationPreferenceCompat.OptimizationIcon(service, id, key) {
                    @Override
                    public void updateIcon() {
                        icon.updateIcon(new Intent());
                    }

                    @Override
                    public void updateIcon(Intent intent) {
                        super.updateIcon(intent);
                        notificationIntent = intent;
                    }

                    @SuppressLint("RestrictedApi")
                    public Notification build(Intent intent) {
                        String targetFile = intent.getStringExtra("targetFile");
                        long cur = intent.getLongExtra("cur", -1);
                        long total = intent.getLongExtra("total", -1);
                        long progress = cur * 100 / total;

                        PendingIntent main;

                        RemoteNotificationCompat.Builder builder;

                        String title;
                        String text;

                        title = getString(R.string.encoding_title);
                        text = ".../" + targetFile + " (" + progress + "%)";
                        builder = new RemoteNotificationCompat.Low(context, R.layout.notifictaion);
                        builder.setViewVisibility(R.id.notification_record, View.VISIBLE);
                        builder.setViewVisibility(R.id.notification_pause, View.GONE);
                        main = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

                        builder.setViewVisibility(R.id.notification_pause, View.GONE);
                        builder.setViewVisibility(R.id.notification_record, View.GONE);

                        builder.setTheme(AudioApplication.getTheme(context, R.style.RecThemeLight, R.style.RecThemeDark))
                                .setChannel(AudioApplication.from(context).channelStatus)
                                .setImageViewTint(R.id.icon_circle, builder.getThemeColor(R.attr.colorButtonNormal))
                                .setTextViewText(R.id.app_name_text, title)
                                .setTitle(title)
                                .setText(text)
                                .setWhen(icon.notification)
                                .setMainIntent(main)
                                .setAdaptiveIcon(R.drawable.ic_launcher_foreground)
                                .setSmallIcon(R.drawable.ic_launcher_notification)
                                .setOngoing(true);

                        return builder.build();
                    }
                };
                icon.create();
            }

            @Override
            public boolean isOptimization() {
                return true; // we are not using optimization preference
            }
        };
        optimization.create();
    }

    @Override
    public void onStartCommand(Intent intent) {
        String a = intent.getAction();
        if (a == null) {
            optimization.icon.updateIcon(intent);
        } else if (a.equals(SHOW_ACTIVITY)) {
            ProximityShader.closeSystemDialogs(this);
            if (intent.getStringExtra("targetFile") == null)
                MainActivity.startActivity(this);
            else
                RecordingActivity.startActivity(this, !intent.getBooleanExtra("recording", false));
        } else if (a.equals(SAVE_AS_WAV)) {
            try {
                File in = (File) intent.getSerializableExtra("in");
                File out = (File) intent.getSerializableExtra("out"); // dir
                out = storage.getNewFile(out, FormatWAV.EXT);
                RawSamples.Info info = new RawSamples.Info(intent.getStringExtra("info"));
                if (encoder == null) {
                    OnFlyEncoding fly = new OnFlyEncoding(storage, out, info);
                    encoder = new FileEncoder(this, in, fly);
                    encoding(encoder, fly, info, new Runnable() {
                        @Override
                        public void run() {
                            encoder.close();
                            encoder = null;
                            startEncoding();
                        }
                    });
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        } else if (a.equals(START_ENCODING)) {
            try {
                File in = (File) intent.getSerializableExtra("in");
                Uri targetUri = intent.getParcelableExtra("targetUri");
                RawSamples.Info info = new RawSamples.Info(intent.getStringExtra("info"));
                if (encoder == null) {
                    OnFlyEncoding fly = new OnFlyEncoding(storage, targetUri, info);
                    encoder = new FileEncoder(this, in, fly);
                    encodingFilters(encoder, fly, info, new Runnable() {
                        @Override
                        public void run() {
                            encoder.close();
                            encoder = null;
                            startEncoding();
                        }
                    });
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
        startEncoding();
    }

    public void startEncoding() {
        if (encoder != null)
            return;
        encodings.load();
        for (File in : encodings.keySet()) {
            EncodingStorage.Info info = encodings.get(in);
            final OnFlyEncoding fly = new OnFlyEncoding(this.storage, info.targetUri, info.info);

            encoder = new FileEncoder(this, in, fly);
            encodingFilters(encoder, fly, info.info, new Runnable() {
                @Override
                public void run() {
                    encoder.close();
                    encoder = null;
                    startEncoding();
                }
            });

            return;
        }
        stopSelf();
    }

    void encodingFilters(final FileEncoder encoder, final OnFlyEncoding fly, final RawSamples.Info info, final Runnable done) {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        if (shared.getBoolean(AudioApplication.PREFERENCE_VOICE, false))
            encoder.filters.add(new VoiceFilter(info));
        float amp = shared.getFloat(AudioApplication.PREFERENCE_VOLUME, 1);
        if (amp != 1)
            encoder.filters.add(new AmplifierFilter(amp));
        if (shared.getBoolean(AudioApplication.PREFERENCE_SKIP, false))
            encoder.filters.add(new SkipSilenceFilter(info));
        encoding(encoder, fly, info, done);
    }

    void encoding(final FileEncoder encoder, final OnFlyEncoding fly, final RawSamples.Info info, final Runnable done) {
        encoder.run(new Runnable() {
            long last = 0;

            @Override
            public void run() {
                try {
                    long cur = encoder.getCurrent();
                    long total = encoder.getTotal();
                    long now = System.currentTimeMillis();
                    Intent intent = new Intent(UPDATE_ENCODING)
                            .putExtra("cur", cur)
                            .putExtra("total", total)
                            .putExtra("info", info.save().toString())
                            .putExtra("targetUri", fly.targetUri)
                            .putExtra("targetFile", Storage.getName(EncodingService.this, fly.targetUri));
                    if (last + 1000 < now) {
                        last = now;
                        sendBroadcast(intent);
                        optimization.icon.updateIcon(intent);
                    }
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new Runnable() {
            @Override
            public void run() { // success
                Storage.delete(encoder.in); // delete raw recording
                Storage.delete(EncodingStorage.jsonFile(encoder.in)); // delete json file
                sendBroadcast(new Intent(DONE_ENCODING)
                        .putExtra("targetUri", fly.targetUri)
                );
                done.run();
            }
        }, new Runnable() {
            @Override
            public void run() { // or error
                Storage.delete(EncodingService.this, fly.targetUri); // fly has fd, delete target manually
                Error(encoder.in, info, encoder.getException());
                stopSelf();
            }
        });
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
