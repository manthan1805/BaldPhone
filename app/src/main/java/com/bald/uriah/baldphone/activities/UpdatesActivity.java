/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.bald.uriah.baldphone.activities;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bald.uriah.baldphone.BuildConfig;
import com.bald.uriah.baldphone.R;
import com.bald.uriah.baldphone.utils.BDB;
import com.bald.uriah.baldphone.utils.BDialog;
import com.bald.uriah.baldphone.utils.BPrefs;
import com.bald.uriah.baldphone.utils.BaldToast;
import com.bald.uriah.baldphone.utils.D;
import com.bald.uriah.baldphone.utils.S;
import com.bald.uriah.baldphone.utils.UpdatingUtil;

import java.io.File;

import static com.bald.uriah.baldphone.utils.UpdatingUtil.getDownloadedFile;
import static com.bald.uriah.baldphone.utils.UpdatingUtil.isMessageOk;

public class UpdatesActivity extends BaldActivity {
    public static final String EXTRA_MESSAGE = "EXTRA_MESSAGE";
    private static final int PROGRESS_DELAY = 200 * D.MILLISECOND;

    private String[] message;
    private DownloadManager manager;
    private long downloadId = -1;
    private BroadcastReceiver downloadFinishedReceiver;
    private Handler handler = new Handler();
    private boolean isProgressCheckerRunning = false;

    private BaldToast notConnectedToast, couldNotStartDownloadToast, downloadFinishedToast, downloadingToast, downloadedFileCouldNotBeDeletedToast, tryNowToast, pleaseBePateint;
    private TextView tv_new_version, tv_current_version, tv_change_log, bt, tv_download_progress, bt_re;
    private ProgressBar pb;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);
        message = getIntent().getStringArrayExtra(EXTRA_MESSAGE);
        if (!isMessageOk(message)) {
            BaldToast.error(this);
            finish();
            return;
        }

        manager = (DownloadManager) this.getSystemService(Context.DOWNLOAD_SERVICE);
        tv_new_version = findViewById(R.id.tv_new_version);
        tv_current_version = findViewById(R.id.tv_current_version);
        tv_change_log = findViewById(R.id.tv_change_log);
        tv_download_progress = findViewById(R.id.tv_download_progress);
        bt_re = findViewById(R.id.bt_re);
        pb = findViewById(R.id.pb);
        bt = findViewById(R.id.bt);

        notConnectedToast = BaldToast.from(this).setType(BaldToast.TYPE_ERROR).setText(R.string.could_not_connect_to_internet);
        couldNotStartDownloadToast = BaldToast.from(this).setType(BaldToast.TYPE_ERROR).setText(R.string.could_not_start_the_download);
        downloadFinishedToast = BaldToast.from(UpdatesActivity.this).setText(R.string.download_finished).setLength(1);
        downloadingToast = BaldToast.from(this).setText(R.string.downloading);
        downloadedFileCouldNotBeDeletedToast = BaldToast.from(this).setType(BaldToast.TYPE_ERROR).setText(R.string.downloaded_update_file_could_not_be_deleted);
        tryNowToast = BaldToast.from(this).setLength(1).setText(R.string.try_now).setBig(true);
        apply();
    }

    @Override
    protected void onDestroy() {
        if (downloadFinishedReceiver != null)
            unregisterReceiver(downloadFinishedReceiver);
        stopProgressChecker();
        super.onDestroy();
    }


    public void apply() {
        if (isDestroyed())
            return;
        tv_new_version.setText(String.format("%s%s", getString(R.string.new_version), message[1]));
        tv_current_version.setText(String.format("%s%s", getString(R.string.current_version), BuildConfig.VERSION_NAME));
        tv_change_log.setText(message[2]);

        final int downloadedVersion = BPrefs.get(this).getInt(BPrefs.LAST_APK_VERSION_KEY, -1);
        final int newVersion = Integer.parseInt(message[0]);

        if (downloadedVersion == newVersion && new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), UpdatingUtil.FILENAME).exists()) {
            bt_re.setVisibility(View.VISIBLE);
            bt_re.setOnClickListener(v -> {
                BPrefs.get(UpdatesActivity.this)
                        .edit()
                        .remove(BPrefs.LAST_APK_VERSION_KEY)
                        .apply();
                tryNowToast.show();
                bt_re.setVisibility(View.GONE);
                bt_re.setOnClickListener(null);
                apply();
            });
            bt.setOnClickListener(v -> {
                install();
            });
            bt.setText(R.string.install);
        } else {
            bt.setText(R.string.download);
            bt.setOnClickListener(v -> {
                final ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (connectivityManager != null) {
                    final NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                    if (networkInfo != null) {
                        if (networkInfo.isConnected()) {
                            onDownloadButtonClick(newVersion);
                        } else {
                            BDB.from(this)
                                    .setTitle(R.string.data_warning)
                                    .setSubText(R.string.data_warning_subtext)
                                    .setDialogState(BDialog.DialogState.YES_CANCEL)
                                    .setCancelable(true)
                                    .setPositiveButtonListener(params -> {
                                        onDownloadButtonClick(newVersion);
                                        return true;
                                    })
                                    .show();
                        }
                    } else {
                        notConnectedToast.show();
                    }
                } else {
                    notConnectedToast.show();
                }
            });
        }
    }

    private void onDownloadButtonClick(final int newVersion) {
        if (downloadApk(newVersion)) {
            bt.setText(R.string.downloading);
            bt.setOnClickListener(D.EMPTY_CLICK_LISTENER);
        } else {
            couldNotStartDownloadToast.show();
        }
    }

    /**
     * @param versionNumber version number
     * @return true if download was started;
     */
    public boolean downloadApk(final int versionNumber) {
        if (manager == null)
            return false;

        downloadingToast.show();
        tv_download_progress.setVisibility(View.VISIBLE);
        deleteCurrentUpdateFile();
        final DownloadManager.Request request =
                new DownloadManager.Request(Uri.parse(UpdatingUtil.APK_URL))
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, UpdatingUtil.FILENAME)
                        .setDescription(getText(R.string.downloading_updates));

        downloadId = manager.enqueue(request);
        downloadFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (downloadId == intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2)) {
                    downloadFinishedToast.show();
                    pb.setVisibility(View.GONE);
                    tv_download_progress.setVisibility(View.GONE);

                    BPrefs.get(UpdatesActivity.this)
                            .edit()
                            .putInt(BPrefs.LAST_APK_VERSION_KEY, versionNumber)
                            .apply();
                    stopProgressChecker();
                    apply();
                }
            }
        };

        this.registerReceiver(downloadFinishedReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        pb.setVisibility(View.VISIBLE);
        startProgressChecker();
        return true;
    }


    private void checkProgress() {
        final Cursor cursor = manager.query(new DownloadManager.Query()
                .setFilterByStatus(~(DownloadManager.STATUS_FAILED | DownloadManager.STATUS_SUCCESSFUL)));
        if (!cursor.moveToFirst()) {
            cursor.close();
            return;
        }
        do {
            final long reference = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_ID));
            if (reference == downloadId) {
                final long progress = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                final long progressMax = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                final int intProgress = (int) ((progress * 100.0) / ((double) progressMax));
                pb.setProgress(intProgress);
                break;
            }


        } while (cursor.moveToNext());
        cursor.close();
    }


    private void startProgressChecker() {
        if (!isProgressCheckerRunning) {
            progressChecker.run();
            isProgressCheckerRunning = true;
        }
    }


    private void stopProgressChecker() {
        handler.removeCallbacks(progressChecker);
        isProgressCheckerRunning = false;
    }

    private final Runnable progressChecker = new Runnable() {
        @Override
        public void run() {
            try {
                checkProgress();
            } finally {
                handler.postDelayed(progressChecker, PROGRESS_DELAY);
            }
        }
    };

    public void install() {
        final File downloadedFile = getDownloadedFile();
        final Uri apkUri = S.fileToUriCompat(downloadedFile, this);
        final Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setData(apkUri)
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        this.startActivity(intent);
    }

    public void deleteCurrentUpdateFile() {
        final File bp = getDownloadedFile();
        if (bp.exists()) {
            if (!bp.delete())
                downloadedFileCouldNotBeDeletedToast.show();
        }
    }


    @Override
    protected int requiredPermissions() {
        return PERMISSION_REQUEST_INSTALL_PACKAGES | PERMISSION_WRITE_EXTERNAL_STORAGE;
    }
}