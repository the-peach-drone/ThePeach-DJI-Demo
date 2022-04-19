package com.dji.mediaManagerDemo;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SlidingDrawer;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.commons.io.FileUtils;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.jetbrains.annotations.NotNull;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dji.common.camera.SettingsDefinitions;
import dji.common.camera.StorageState;
import dji.common.error.DJICameraError;
import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.log.DJILog;
import dji.sdk.base.BaseProduct;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.FetchMediaTask;
import dji.sdk.media.FetchMediaTaskContent;
import dji.sdk.media.FetchMediaTaskScheduler;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaManager;

public class MainActivity extends Activity implements View.OnClickListener {

    private static final String TAG = MainActivity.class.getName();

    private Button mBackBtn, mDeleteBtn, mReloadBtn, mDownloadBtn, mUploadBtn, mSettingBtn;
    private RecyclerView listView;
    private FileListAdapter mListAdapter;
    private List<MediaFile> mediaFileList = new ArrayList<MediaFile>();
    private MediaManager mMediaManager;
    private MediaManager.FileListState currentFileListState = MediaManager.FileListState.UNKNOWN;
    private FetchMediaTaskScheduler scheduler;
    private ProgressDialog mLoadingDialog;
    private ProgressDialog mDownloadDialog;
    private SlidingDrawer mPushDrawerSd;
    private int currentProgress = -1;
    private ImageView mDisplayImageView;
    private int lastClickViewIndex =-1;
    private View lastClickView;
    private TextView mPushTv;
    private SettingsDefinitions.StorageLocation storageLocation;

    // FTP variable
    private final String FTP_TAG = "Connect FTP";
    public FTPClient mFTPClient = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
        DemoApplication.getAircraftInstance().getCamera().setStorageStateCallBack(new StorageState.Callback() {
            @Override
            public void onUpdate(@NonNull @NotNull StorageState storageState) {
                if(storageState.isInserted()) {
                    storageLocation = SettingsDefinitions.StorageLocation.SDCARD;
                    DemoApplication.getAircraftInstance().getCamera().setStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                        }
                    });
                } else {
                    storageLocation = SettingsDefinitions.StorageLocation.INTERNAL_STORAGE;
                    DemoApplication.getAircraftInstance().getCamera().setStorageLocation(SettingsDefinitions.StorageLocation.INTERNAL_STORAGE, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                        }
                    });
                }
            }
        });

        // FTP init
        mFTPClient = new FTPClient();
    }

    @Override
    protected void onResume() {
        super.onResume();
        initMediaManager();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lastClickView = null;
        if (mMediaManager != null) {
            mMediaManager.stop(null);
            mMediaManager.removeFileListStateCallback(this.updateFileListStateListener);
            mMediaManager.removeMediaUpdatedVideoPlaybackStateListener(updatedVideoPlaybackStateListener);
            mMediaManager.exitMediaDownloading();
            if (scheduler!=null) {
                scheduler.removeAllTasks();
            }
        }

        if (DemoApplication.getCameraInstance() != null) {
            if (isMavicAir2() || isAir2S() || isM300()) {
                DemoApplication.getCameraInstance().exitPlayback(djiError -> {
                    if (djiError != null) {
                        DemoApplication.getCameraInstance().setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, djiError1 -> {
                            if (djiError1 != null) {
                                setResultToToast("Set PHOTO_SINGLE Mode Failed. " + djiError1.getDescription());
                            }
                        });
                    }
                });
            } else {
                DemoApplication.getCameraInstance().setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, djiError -> {
                    if (djiError != null) {
                        setResultToToast("Set SHOOT_PHOTO Mode Failed. " + djiError.getDescription());
                    }
                });
            }
        }

        if (mediaFileList != null) {
            mediaFileList.clear();
        }
        super.onDestroy();
    }

    void initUI() {

        //Init RecyclerView
        listView = (RecyclerView) findViewById(R.id.filelistView);
        LinearLayoutManager layoutManager = new LinearLayoutManager(MainActivity.this, RecyclerView.VERTICAL,false);
        listView.setLayoutManager(layoutManager);

        //Init FileListAdapter
        mListAdapter = new FileListAdapter();
        listView.setAdapter(mListAdapter);

        //Init Loading Dialog
        mLoadingDialog = new ProgressDialog(MainActivity.this);
        mLoadingDialog.setMessage("Please wait");
        mLoadingDialog.setCanceledOnTouchOutside(false);
        mLoadingDialog.setCancelable(false);

        //Init Download Dialog
        mDownloadDialog = new ProgressDialog(MainActivity.this);
        mDownloadDialog.setTitle("Downloading file");
        mDownloadDialog.setIcon(android.R.drawable.ic_dialog_info);
        mDownloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mDownloadDialog.setCanceledOnTouchOutside(false);
        mDownloadDialog.setCancelable(true);
        mDownloadDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                if (mMediaManager != null) {
                    mMediaManager.exitMediaDownloading();
                }
            }
        });

        mPushDrawerSd = (SlidingDrawer)findViewById(R.id.pointing_drawer_sd);
        mPushTv = (TextView)findViewById(R.id.pointing_push_tv);
        mBackBtn = (Button) findViewById(R.id.back_btn);
        mDeleteBtn = (Button) findViewById(R.id.delete_btn);
        mDownloadBtn = (Button) findViewById(R.id.download_btn);
        mUploadBtn = (Button) findViewById(R.id.upload_btn);
        mSettingBtn = (Button) findViewById(R.id.setting_btn);
        mReloadBtn = (Button) findViewById(R.id.reload_btn);
        mDisplayImageView = (ImageView) findViewById(R.id.imageView);
        mDisplayImageView.setVisibility(View.VISIBLE);

        mBackBtn.setOnClickListener(this);
        mDeleteBtn.setOnClickListener(this);
        mDownloadBtn.setOnClickListener(this);
        mSettingBtn.setOnClickListener(this);
        mUploadBtn.setOnClickListener(this);
        mReloadBtn.setOnClickListener(this);
        mDownloadBtn.setOnClickListener(this);
    }

    private void showProgressDialog() {
        runOnUiThread(new Runnable() {
            public void run() {
                if (mLoadingDialog != null) {
                    mLoadingDialog.show();
                }
            }
        });
    }

    private void hideProgressDialog() {
        runOnUiThread(new Runnable() {
            public void run() {
                if (null != mLoadingDialog && mLoadingDialog.isShowing()) {
                    mLoadingDialog.dismiss();
                }
            }
        });
    }

    private void ShowDownloadProgressDialog() {
        if (mDownloadDialog != null) {
            runOnUiThread(new Runnable() {
                public void run() {
                    mDownloadDialog.incrementProgressBy(-mDownloadDialog.getProgress());
                    mDownloadDialog.show();
                }
            });
        }
    }

    private void HideDownloadProgressDialog() {
        if (null != mDownloadDialog && mDownloadDialog.isShowing()) {
            runOnUiThread(new Runnable() {
                public void run() {
                    mDownloadDialog.dismiss();
                }
            });
        }
    }

    private void setResultToToast(final String result) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, result, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setResultToText(final String string) {
        if (mPushTv == null) {
            setResultToToast("Push info tv has not be init...");
        }
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mPushTv.setText(string);
            }
        });
    }

    private void initMediaManager() {
        if (DemoApplication.getProductInstance() == null) {
            mediaFileList.clear();
            mListAdapter.notifyDataSetChanged();
            DJILog.e(TAG, "Product disconnected");
            return;
        } else {
            if (null != DemoApplication.getCameraInstance() && DemoApplication.getCameraInstance().isMediaDownloadModeSupported()) {
                mMediaManager = DemoApplication.getCameraInstance().getMediaManager();
                if (null != mMediaManager) {
                    mMediaManager.addUpdateFileListStateListener(this.updateFileListStateListener);
                    mMediaManager.addMediaUpdatedVideoPlaybackStateListener(this.updatedVideoPlaybackStateListener);
                    if (isMavicAir2() || isAir2S() || isM300()) {
                        DemoApplication.getCameraInstance().enterPlayback(djiError -> {
                            if (djiError == null) {
                                DJILog.e(TAG, "Set cameraMode success");
                                showProgressDialog();
                                getFileList();
                            } else {
                                setResultToToast("Set cameraMode failed");
                            }
                        });
                    } else {
                        DemoApplication.getCameraInstance().setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, error -> {
                            if (error == null) {
                                DJILog.e(TAG, "Set cameraMode success");
                                showProgressDialog();
                                getFileList();
                            } else {
                                setResultToToast("Set cameraMode failed");
                            }
                        });
                    }

                    if (mMediaManager.isVideoPlaybackSupported()) {
                        DJILog.e(TAG, "Camera support video playback!");
                    } else {
                        setResultToToast("Camera does not support video playback!");
                    }
                    scheduler = mMediaManager.getScheduler();
                }

            } else if (null != DemoApplication.getCameraInstance()
                    && !DemoApplication.getCameraInstance().isMediaDownloadModeSupported()) {
                setResultToToast("Media Download Mode not Supported");
            }
        }
        return;
    }

    private void getFileList() {
        mMediaManager = DemoApplication.getCameraInstance().getMediaManager();
        if (mMediaManager != null) {

            if ((currentFileListState == MediaManager.FileListState.SYNCING) || (currentFileListState == MediaManager.FileListState.DELETING)){
                DJILog.e(TAG, "Media Manager is busy.");
            }else{
                mMediaManager.refreshFileListOfStorageLocation(storageLocation, djiError -> {
                    if (null == djiError) {
                        hideProgressDialog();

                        //Reset data
                        if (currentFileListState != MediaManager.FileListState.INCOMPLETE) {
                            mediaFileList.clear();
                            lastClickViewIndex = -1;
                            lastClickView = null;
                        }

                        List<MediaFile> tempList;
                        if (storageLocation == SettingsDefinitions.StorageLocation.SDCARD) {
                            tempList = mMediaManager.getSDCardFileListSnapshot();
                        } else {
                            tempList = mMediaManager.getInternalStorageFileListSnapshot();
                        }
                        if (tempList != null) {
                            for(int i = 0; i < tempList.size(); i++) {
                                if(tempList.get(i).getMediaType() == MediaFile.MediaType.MOV || tempList.get(i).getMediaType() == MediaFile.MediaType.MP4) {
                                    tempList.remove(i);
                                }
                                else {
                                    mediaFileList.add(tempList.get(i));
                                }
                            }
                        }
                        if (mediaFileList != null) {
                            Collections.sort(mediaFileList, (lhs, rhs) -> {
                                if (lhs.getTimeCreated() < rhs.getTimeCreated()) {
                                    return 1;
                                } else if (lhs.getTimeCreated() > rhs.getTimeCreated()) {
                                    return -1;
                                }
                                return 0;
                            });
                        }
                        scheduler.resume(error -> {
                            if (error == null) {
                                getThumbnails();
                            }
                        });
                    } else {
                        hideProgressDialog();
                        setResultToToast("Get Media File List Failed:" + djiError.getDescription());
                    }
                });
            }
        }
    }

    private void getThumbnails() {
        if (mediaFileList.size() <= 0) {
            setResultToToast("No File info for downloading thumbnails");
            return;
        }
        for (int i = 0; i < mediaFileList.size(); i++) {
            getThumbnailByIndex(i);
        }
    }

    private FetchMediaTask.Callback taskCallback = new FetchMediaTask.Callback() {
        @Override
        public void onUpdate(MediaFile file, FetchMediaTaskContent option, DJIError error) {
            if (null == error) {
                if (option == FetchMediaTaskContent.PREVIEW) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            mListAdapter.notifyDataSetChanged();
                        }
                    });
                }
                if (option == FetchMediaTaskContent.THUMBNAIL) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            mListAdapter.notifyDataSetChanged();
                        }
                    });
                }
            } else {
                DJILog.e(TAG, "Fetch Media Task Failed" + error.getDescription());
            }
        }
    };

    private void getThumbnailByIndex(final int index) {
        FetchMediaTask task = new FetchMediaTask(mediaFileList.get(index), FetchMediaTaskContent.THUMBNAIL, taskCallback);
        scheduler.moveTaskToEnd(task);
    }

    private static class ItemHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail_img;
        TextView file_name;
        TextView file_type;
        TextView file_size;
        TextView file_time;

        public ItemHolder(View itemView) {
            super(itemView);
            this.thumbnail_img = (ImageView) itemView.findViewById(R.id.filethumbnail);
            this.file_name = (TextView) itemView.findViewById(R.id.filename);
            this.file_type = (TextView) itemView.findViewById(R.id.filetype);
            this.file_size = (TextView) itemView.findViewById(R.id.fileSize);
            this.file_time = (TextView) itemView.findViewById(R.id.filetime);
        }
    }

    private class FileListAdapter extends RecyclerView.Adapter<ItemHolder> {
        @Override
        public int getItemCount() {
            if (mediaFileList != null) {
                return mediaFileList.size();
            }
            return 0;
        }

        @Override
        public ItemHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.media_info_item, parent, false);
            return new ItemHolder(view);
        }

        @Override
        public void onBindViewHolder(ItemHolder mItemHolder, final int index) {

            final MediaFile mediaFile = mediaFileList.get(index);
            if (mediaFile != null) {
                if (mediaFile.getMediaType() != MediaFile.MediaType.MOV && mediaFile.getMediaType() != MediaFile.MediaType.MP4) {
                    mItemHolder.file_time.setVisibility(View.GONE);
                } else {
                    mItemHolder.file_time.setVisibility(View.VISIBLE);
                    mItemHolder.file_time.setText(mediaFile.getDurationInSeconds() + " s");
                }
                mItemHolder.file_name.setText(mediaFile.getFileName());
                mItemHolder.file_type.setText(mediaFile.getMediaType().name());
                mItemHolder.file_size.setText(FileUtils.byteCountToDisplaySize(mediaFile.getFileSize()));
                mItemHolder.thumbnail_img.setImageBitmap(mediaFile.getThumbnail());
                mItemHolder.thumbnail_img.setOnClickListener(ImgOnClickListener);
                mItemHolder.thumbnail_img.setTag(mediaFile);
                mItemHolder.itemView.setTag(index);

                if (lastClickViewIndex == index) {
                    mItemHolder.itemView.setSelected(true);
                } else {
                    mItemHolder.itemView.setSelected(false);
                }
                mItemHolder.itemView.setOnClickListener(itemViewOnClickListener);
            }
        }
    }

    private View.OnClickListener itemViewOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            lastClickViewIndex = (int) (v.getTag());

            if (lastClickView != null && lastClickView != v) {
                lastClickView.setSelected(false);
            }
            v.setSelected(true);
            lastClickView = v;
        }
    };

    private View.OnClickListener ImgOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            MediaFile selectedMedia = (MediaFile) v.getTag();
            if (selectedMedia != null && mMediaManager != null) {
                addMediaTask(selectedMedia);
            }
        }
    };

    private void addMediaTask(final MediaFile mediaFile) {
        final FetchMediaTaskScheduler scheduler = mMediaManager.getScheduler();
        final FetchMediaTask task =
                new FetchMediaTask(mediaFile, FetchMediaTaskContent.PREVIEW, new FetchMediaTask.Callback() {
                    @Override
                    public void onUpdate(final MediaFile mediaFile, FetchMediaTaskContent fetchMediaTaskContent, DJIError error) {
                        if (null == error) {
                            if (mediaFile.getPreview() != null) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        final Bitmap previewBitmap = mediaFile.getPreview();
                                        mDisplayImageView.setVisibility(View.VISIBLE);
                                        mDisplayImageView.setImageBitmap(previewBitmap);
                                    }
                                });
                            } else {
                                setResultToToast("null bitmap!");
                            }
                        } else {
                            setResultToToast("fetch preview image failed: " + error.getDescription());
                        }
                    }
                });

        scheduler.resume(error -> {
            if (error == null) {
                scheduler.moveTaskToNext(task);
            } else {
                setResultToToast("resume scheduler failed: " + error.getDescription());
            }
        });
    }

    //Listeners
    private MediaManager.FileListStateListener updateFileListStateListener = state -> currentFileListState = state;

    private MediaManager.VideoPlaybackStateListener updatedVideoPlaybackStateListener =
            new MediaManager.VideoPlaybackStateListener() {
                @Override
                public void onUpdate(MediaManager.VideoPlaybackState videoPlaybackState) {
                    updateStatusTextView(videoPlaybackState);
                }
            };

    private void updateStatusTextView(MediaManager.VideoPlaybackState videoPlaybackState) {
        final StringBuffer pushInfo = new StringBuffer();

        addLineToSB(pushInfo, "Video Playback State", null);
        if (videoPlaybackState != null) {
            if (videoPlaybackState.getPlayingMediaFile() != null) {
                addLineToSB(pushInfo, "media index", videoPlaybackState.getPlayingMediaFile().getIndex());
                addLineToSB(pushInfo, "media size", videoPlaybackState.getPlayingMediaFile().getFileSize());
                addLineToSB(pushInfo,
                        "media duration",
                        videoPlaybackState.getPlayingMediaFile().getDurationInSeconds());
                addLineToSB(pushInfo, "media created date", videoPlaybackState.getPlayingMediaFile().getDateCreated());
                addLineToSB(pushInfo,
                        "media orientation",
                        videoPlaybackState.getPlayingMediaFile().getVideoOrientation());
            } else {
                addLineToSB(pushInfo, "media index", "None");
            }
            addLineToSB(pushInfo, "media current position", videoPlaybackState.getPlayingPosition());
            addLineToSB(pushInfo, "media current status", videoPlaybackState.getPlaybackStatus());
            addLineToSB(pushInfo, "media cached percentage", videoPlaybackState.getCachedPercentage());
            addLineToSB(pushInfo, "media cached position", videoPlaybackState.getCachedPosition());
            pushInfo.append("\n");
            setResultToText(pushInfo.toString());
        }
    }

    private void addLineToSB(StringBuffer sb, String name, Object value) {
        if (sb == null) return;
        sb.
                append((name == null || "".equals(name)) ? "" : name + ": ").
                append(value == null ? "" : value + "").
                append("\n");
    }

    private void downloadFileByIndex(final int index){
        if ((mediaFileList.get(index).getMediaType() == MediaFile.MediaType.PANORAMA)
                || (mediaFileList.get(index).getMediaType() == MediaFile.MediaType.SHALLOW_FOCUS)) {
            return;
        }

        // use internal avoid permission problem
        File destDir_internal = new File(getFilesDir(), "Images_Copy");

        mediaFileList.get(index).fetchFileData(destDir_internal, null, new DownloadListener<String>() {
            @Override
            public void onFailure(DJIError error) {
                currentProgress = -1;
                HideDownloadProgressDialog();
                setResultToToast("Download File Failed" + error.getDescription());
            }

            @Override
            public void onProgress(long total, long current) {
            }

            @Override
            public void onRateUpdate(long total, long current, long perSize) {
                int tmpProgress = (int) (1.0 * current / total * 100);
                if (tmpProgress != currentProgress) {
                    mDownloadDialog.setProgress(tmpProgress);
                    currentProgress = tmpProgress;
                }
            }

            @Override
            public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {

            }

            @Override
            public void onStart() {
                currentProgress = -1;
                ShowDownloadProgressDialog();
            }

            @Override
            public void onSuccess(String filePath) {
                currentProgress = -1;
                HideDownloadProgressDialog();

                // Copy internal file to external
                String fileName_Image = destDir_internal.getPath() + "/" + mediaFileList.get(index).getFileName();
                File image_File = new File(fileName_Image);
                if(image_File.exists()) {
                    // Copy file
                    try {
                        saveFileToExternal(image_File, getImageMimeType(fileName_Image), mediaFileList.get(index).getFileName());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Delete internal cache
                    image_File.delete();
                }

                setResultToToast("Download File Success" + ":" + filePath);
            }
        });
    }

    private void uploadFileToFTP(final int index) {
        setResultToToast("Download file before upload.");

        if ((mediaFileList.get(index).getMediaType() == MediaFile.MediaType.PANORAMA)
                || (mediaFileList.get(index).getMediaType() == MediaFile.MediaType.SHALLOW_FOCUS)) {
            return;
        }

        // use internal avoid permission problem
        File destDir_internal = new File(getFilesDir(), "Images");

        mediaFileList.get(index).fetchFileData(destDir_internal, null, new DownloadListener<String>() {
            @Override
            public void onFailure(DJIError error) {
                currentProgress = -1;
                HideDownloadProgressDialog();
                setResultToToast("Download File Failed" + error.getDescription());
            }

            @Override
            public void onProgress(long total, long current) {
            }

            @Override
            public void onRateUpdate(long total, long current, long persize) {
                int tmpProgress = (int) (1.0 * current / total * 100);
                if (tmpProgress != currentProgress) {
                    mDownloadDialog.setProgress(tmpProgress);
                    currentProgress = tmpProgress;
                }
            }

            @Override
            public void onRealtimeDataUpdate(byte[] bytes, long l, boolean b) {

            }

            @Override
            public void onStart() {
                currentProgress = -1;
                ShowDownloadProgressDialog();
            }

            @Override
            public void onSuccess(String filePath) {
                currentProgress = -1;
                HideDownloadProgressDialog();

                // Get FTP ENV
                SharedPreferences ftpEnv = getSharedPreferences("FTP_ENV", MODE_PRIVATE);
                String settingIP = ftpEnv.getString("ftpHost", "");
                String settingPort = ftpEnv.getString("ftpPort", "");
                String settingUser = ftpEnv.getString("ftpUser", "");
                String settingPass = ftpEnv.getString("ftpPass", "");

                // FTP Upload
                Thread ftp_Thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean ftp_Status = ftpConnect(settingIP, settingUser, settingPass, Integer.valueOf(settingPort));
                        if(ftp_Status) {
                            String srcFilePath = destDir_internal.getPath() + "/" + mediaFileList.get(index).getFileName();
                            String desFileName = mediaFileList.get(index).getFileName();
                            String desDirectory = ftpGetDirectory();

                            boolean ftp_File = ftpUploadFile(srcFilePath, desFileName, desDirectory);
                            if(ftp_File) {
                                setResultToToast("File upload.");
                            }
                            else {
                                setResultToToast("File upload fail.");
                            }
                            ftpDisconnect();
                        }
                        else {
                            setResultToToast("Can't find host.");
                        }
                        // Delete internal file
                        String fileName_Image = destDir_internal.getPath() + "/" + mediaFileList.get(index).getFileName();
                        File image_File_Delete = new File(fileName_Image);
                        if(image_File_Delete.exists()) {
                            image_File_Delete.delete();
                        }
                    }
                });
                ftp_Thread.start();
            }
        });
    }

    private void deleteFileByIndex(final int index) {
        ArrayList<MediaFile> fileToDelete = new ArrayList<MediaFile>();
        if (mediaFileList.size() > index) {
            fileToDelete.add(mediaFileList.get(index));
            mMediaManager.deleteFiles(fileToDelete, new CommonCallbacks.CompletionCallbackWithTwoParam<List<MediaFile>, DJICameraError>() {
                @Override
                public void onSuccess(List<MediaFile> x, DJICameraError y) {
                    DJILog.e(TAG, "Delete file success");
                    runOnUiThread(new Runnable() {
                        public void run() {
                            MediaFile file = mediaFileList.remove(index);

                            // Reset select view
                            lastClickViewIndex = -1;
                            lastClickView = null;

                            // Update recyclerView
                            mListAdapter.notifyItemRemoved(index);
                        }
                    });
                }

                @Override
                public void onFailure(DJIError error) {
                    setResultToToast("Delete file failed");
                }
            });
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.back_btn: {
                this.finish();
                break;
            }
            case R.id.delete_btn:{
                if(lastClickViewIndex < 0) {
                    setResultToToast("First select image before delete.");
                }
                else {
                    deleteFileByIndex(lastClickViewIndex);
                }
                break;
            }
            case R.id.reload_btn: {
                getFileList();
                break;
            }
            case R.id.download_btn: {
                if(lastClickViewIndex < 0) {
                    setResultToToast("First select image before download.");
                }
                else {
                    downloadFileByIndex(lastClickViewIndex);
                }
                break;
            }
            case R.id.upload_btn: {
                if(lastClickViewIndex < 0) {
                    setResultToToast("First select image before upload.");
                }
                else {
                    uploadFileToFTP(lastClickViewIndex);
                }
                break;
            }
            case R.id.setting_btn: {
                Intent settingDataIntent = new Intent(this, popup_Settings.class);
                startActivityForResult(settingDataIntent, 1);
                break;
            }
            default:
                break;
        }
    }

    private boolean isMavicAir2() {
        BaseProduct baseProduct = DemoApplication.getProductInstance();
        if (baseProduct != null) {
            return baseProduct.getModel() == Model.MAVIC_AIR_2;
        }
        return false;
    }

    private boolean isAir2S() {
        BaseProduct baseProduct = DemoApplication.getProductInstance();
        if (baseProduct != null) {
            return baseProduct.getModel() == Model.DJI_AIR_2S;
        }
        return false;
    }

    private boolean isM300() {
        BaseProduct baseProduct = DemoApplication.getProductInstance();
        if (baseProduct != null) {
            return baseProduct.getModel() == Model.MATRICE_300_RTK;
        }
        return false;
    }

    private static byte[] convertFileToByteArray(File file) {
        FileInputStream fis = null;

        // Create byteArray of same length as file
        byte[] byteArray = new byte[(int) file.length()];

        try {
            fis = new FileInputStream(file);

            // Read file to byte array
            fis.read(byteArray);
            fis.close();
        }catch (IOException ioExp) {
            ioExp.printStackTrace();
        }finally {
            if(fis != null) {
                try {
                    fis.close();
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return byteArray;
    }

    private String getImageMimeType(String imageUrl) {
        String[] type_Arr = imageUrl.split("\\.");
        if(type_Arr[(type_Arr.length) - 1].toLowerCase().equals("jpg")) {
            return "image/jpeg";
        }
        else {
            return "image/" + type_Arr[(type_Arr.length - 1)].toLowerCase();
        }
    }

    public void saveFileToExternal(@NonNull final File file, @NonNull final String mimeType, @NonNull final String displayName) throws IOException {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // protect file before update
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        final ContentResolver resolver = getContentResolver();
        Uri uri = null;

        try {
            final Uri contentUri;
            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            uri = resolver.insert(contentUri, values);

            if(uri == null) {
                throw new IOException("Failed to create new MediaStore record.");
            }
            try (final OutputStream stream = resolver.openOutputStream(uri)) {
                if(stream == null) {
                    throw new IOException("Failed to open output stream.");
                }
                // Copy File(Byte Stream)
                byte[] byteArray = convertFileToByteArray(file);
                stream.write(byteArray);
                stream.flush();
                stream.close();

                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();

                    // Pending set 0
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    // File update
                    resolver.update(uri, values, null, null);
                    setResultToToast("Image Saved.");
                }
            }
        }
        catch (IOException e) {
            setResultToToast("Unable save image.");

            if(uri != null) {
                resolver.delete(uri, null, null);
            }
            throw e;
        }
    }

    // FTP Connect
    private boolean ftpConnect(String host, String username, String password, int port) {
        boolean result = false;
        try{
            mFTPClient.connect(host, port);

            if(FTPReply.isPositiveCompletion(mFTPClient.getReplyCode())) {
                result = mFTPClient.login(username, password);
                mFTPClient.enterLocalPassiveMode();
            }
        }catch (Exception e){
            DJILog.e(FTP_TAG, "Couldn't connect to host");
        }
        return result;
    }

    // FTP Disconnect
    private boolean ftpDisconnect() {
        boolean result = false;
        try {
            mFTPClient.logout();
            mFTPClient.disconnect();
            result = true;
        } catch (Exception e) {
            DJILog.e(FTP_TAG, "Failed to disconnect with server");
        }
        return result;
    }

    private boolean ftpChangeDirctory(String directory) {
        try{
            mFTPClient.changeWorkingDirectory(directory);
            return true;
        }catch (Exception e){
            DJILog.e(FTP_TAG, "Couldn't change the directory");
        }
        return false;
    }

    // FTP File Upload
    private boolean ftpUploadFile(String srcFilePath, String desFileName, String desDirectory) {
        boolean result = false;
        try {
            FileInputStream fis = new FileInputStream(srcFilePath);
            if(ftpChangeDirctory(desDirectory)) {
                mFTPClient.setFileType(FTP.BINARY_FILE_TYPE);
                mFTPClient.setFileTransferMode(FTP.BINARY_FILE_TYPE);
                result = mFTPClient.storeFile(desFileName, fis);
            }
            fis.close();
        } catch(Exception e){
            DJILog.e(FTP_TAG, "Couldn't upload the file");
        }
        return result;
    }

    // FTP Get current directory
    private String ftpGetDirectory(){
        String directory = null;
        try{
            directory = mFTPClient.printWorkingDirectory();
        } catch (Exception e){
            DJILog.e(FTP_TAG, "Couldn't get current directory");
        }
        return directory;
    }
}
