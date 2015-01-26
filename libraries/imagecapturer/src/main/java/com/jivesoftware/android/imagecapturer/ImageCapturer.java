/*
 * Copyright 2015 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.android.imagecapturer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ImageCapturer implements Parcelable {
    public static final Creator<ImageCapturer> CREATOR =
            new Creator<ImageCapturer>() {
                public ImageCapturer createFromParcel(Parcel in) {
                    ImageCapturer imageCapturer = new ImageCapturer();
                    imageCapturer.imageSourceChooserTitle = in.readString();
                    String imageTemporaryFileAbsolutePath = in.readString();
                    // Android Studio stupidly thinks imageTemporaryFileAbsolutePath can't be null. It is wrong.
                    //noinspection ConstantConditions
                    if (imageTemporaryFileAbsolutePath != null) {
                        imageCapturer.imageTemporaryFile = new File(imageTemporaryFileAbsolutePath);
                    }
                    imageCapturer.requestCode = in.readInt();
                    return imageCapturer;
                }

                public ImageCapturer[] newArray(int size) {
                    return new ImageCapturer[size];
                }
            };

    private String imageSourceChooserTitle;
    private File imageTemporaryFile;
    private int requestCode = -1;
    private DecodeReceivedImageAsyncTask decodeReceivedImageAsyncTask;

    public void setImageSourceChooserTitle(String imageSourceChooserTitle) {
        this.imageSourceChooserTitle = imageSourceChooserTitle;
    }

    /**
     * Start a new {@link android.app.Activity} to choose an image.
     *
     * @param activity    the current {@link android.app.Activity}
     * @param requestCode the requestCode you want Android to pass back when it calls
     *                    <code>activity</code>'s {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}
     * @throws java.io.IOException
     */
    public void awaitImageCapture(Activity activity, int requestCode) throws IOException {
        assertOnMainThread();

        if (requestCode < 1) {
            throw new IllegalArgumentException("requestCode must be greater than zero: " + requestCode);
        }

        if (imageTemporaryFile != null) {
            throw new IllegalStateException("Can only capture one file at a time. Previous file: " + imageTemporaryFile);
        }

        PackageManager packageManager = activity.getPackageManager();
        String title;
        if (imageSourceChooserTitle == null) {
            title = "Select Image Source";
        } else {
            title = imageSourceChooserTitle;
        }

        String tempFileName = "capture-" + UUID.randomUUID().toString() + ".jpg";
        File tempDir = new File(Environment.getExternalStorageDirectory(), "captured-images");
        if (!tempDir.exists()) {
            if (!tempDir.mkdir()) {
                throw new IOException("Couldn't create temporary image storage directory: " + tempDir);
            }
        }
        imageTemporaryFile = new File(tempDir, tempFileName);

        final List<Intent> galleryIntents = new ArrayList<Intent>();
        final Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
        galleryIntent.setType("image/*");

        final List<ResolveInfo> listGalleryListeners = packageManager.queryIntentActivities(galleryIntent, 0);
        for (ResolveInfo res : listGalleryListeners) {
            final String packageName = res.activityInfo.packageName;
            final Intent intent = new Intent(galleryIntent);
            intent.setComponent(new ComponentName(res.activityInfo.packageName, res.activityInfo.name));
            intent.setPackage(packageName);
            galleryIntents.add(intent);
        }

        // camera intent
        final Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(imageTemporaryFile));

        final Intent chooserIntent = Intent.createChooser(cameraIntent, title);

        // Add the gallery options to the menu.
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, galleryIntents.toArray(new Parcelable[galleryIntents.size()]));
        this.requestCode = requestCode;
        activity.startActivityForResult(chooserIntent, requestCode);
    }

    /**
     * Call this from {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}.
     * <p/>
     * It is safe to call {@link #awaitImageCapture(android.app.Activity, int)} after this call.
     *
     * @param activity              your {@link android.app.Activity}
     * @param requestCode           from {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}.
     * @param resultCode            from {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}.
     * @param data                  from {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}.
     * @param imageCapturedCallback called when background PROCESSING finishes.
     * @param backgroundBitmapProvider customizes created {@link android.graphics.Bitmap}s. May be <code>null</code>.
     * @return {@link Result#IGNORED} if <code>requestCode</code> doesn't match <code>requestCode</code>
     * given to {@link #awaitImageCapture}. {@link Result#PROCESSING} if the image capture {@link android.content.Intent}
     * succeeded and we are PROCESSING the image in the background. {@link Result#FAILED} if the image
     * capture {@link android.content.Intent} FAILED.
     */
    public Result onActivityResult(Activity activity, int requestCode, int resultCode, Intent data, ImageCapturedCallback imageCapturedCallback, BackgroundBitmapProvider backgroundBitmapProvider) {
        if (this.requestCode == requestCode) {
            assertOnMainThread();

            if (imageTemporaryFile == null) {
                throw new IllegalStateException("awaitImageCapture wasn't called first. Or maybe you didn't save/restore your ImageCapturer in onSaveInstanceState/onCreate");
            }

            Result result;
            if (resultCode == Activity.RESULT_OK) {
                decodeReceivedImageAsyncTask = new DecodeReceivedImageAsyncTask(imageTemporaryFile, activity, data, imageCapturedCallback, backgroundBitmapProvider);
                decodeReceivedImageAsyncTask.execute((Void[]) null);
                result = Result.PROCESSING;
            } else {
                result = Result.FAILED;
            }

            imageTemporaryFile = null;
            this.requestCode = -1;

            return result;
        } else {
            return Result.IGNORED;
        }
    }

    /**
     * Ignore results from prior {@link #awaitImageCapture(android.app.Activity, int)} calls.
     * <p/>
     * It is safe to call {@link #awaitImageCapture(android.app.Activity, int)} after this call.
     */
    public void cancelAwaitImageCapture() {
        assertOnMainThread();

        imageTemporaryFile = null;
        requestCode = -1;
    }

    /**
     * Stop background PROCESSING from prior {@link #onActivityResult} calls.
     * <p/>
     * It is safe to call {@link #awaitImageCapture(android.app.Activity, int)} after this call.
     */
    public void cancelBackgroundProcessing() {
        assertOnMainThread();

        if (decodeReceivedImageAsyncTask != null) {
            decodeReceivedImageAsyncTask.cancel(true);
            decodeReceivedImageAsyncTask = null;
        }
    }

    public boolean isCapturing() {
        return imageTemporaryFile != null;
    }

    private void assertOnMainThread() {
        Thread currentThread = Thread.currentThread();
        Thread mainThread = Looper.getMainLooper().getThread();
        if (mainThread != currentThread) {
            throw new IllegalStateException("ImageCapturer can only be used from the main thread. Main thread: " + mainThread + "Current thread: " + currentThread);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(imageSourceChooserTitle);
        String imageTemporaryFileAbsolutePath;
        if (imageTemporaryFile == null) {
            imageTemporaryFileAbsolutePath = null;
        } else {
            imageTemporaryFileAbsolutePath = imageTemporaryFile.getAbsolutePath();
        }
        dest.writeString(imageTemporaryFileAbsolutePath);
        dest.writeInt(requestCode);
    }

    public interface BackgroundBitmapProvider {
        /**
         * Call on background thread when a image needs to be converted to a {@link android.graphics.Bitmap}.
         *
         * @param imageAssetFileDescriptor The {@link android.content.res.AssetFileDescriptor} or the image.
         * @return a {@link android.graphics.Bitmap} for the image or <code>null</code> if it couldn't be created.
         */
        Bitmap provideBitmap(AssetFileDescriptor imageAssetFileDescriptor) throws IOException;
    }

    public interface ImageCapturedCallback {
        /**
         * Called on main thread when an image is captured.
         * Only one of <code>imageUri</code> or <code>imageFile</code> will be non-<code>null</code>.
         * This returns both a {@link android.net.Uri} and a {@link java.io.File} instead of just
         * a <code>Uri</code> because when possible, you might want to delete the <code>File</code>
         * when the bitmap is returned, but the <code>Uri</code> may be from a {@link android.content.ContentProvider},
         * which may not support deleting an item at that <code>Uri</code>.
         *
         * @param bitmap    A {@link android.graphics.Bitmap} of the captured image
         * @param imageUri  The {@link android.net.Uri} of the captured image or <code>null</code>.
         * @param imageFile The {@link java.io.File} of the captured image or <code>null</code>.
         */
        void onImageCaptured(Bitmap bitmap, Uri imageUri, File imageFile);

        /**
         * Called on main thread when capturing an image fails with a {@link java.io.IOException}
         */
        void onImageCaptureFailed(IOException e);

        /**
         * Called on main thread when capturing an image fails with an {@link java.lang.OutOfMemoryError}
         * or a {@link java.lang.NullPointerException} which is usually caused by an <code>OutOfMemoryError</code>
         * inside of Android.
         * Only one of <code>outOfMemoryError</code> or <code>npe</code> will be non-<code>null</code>.
         *
         * @param outOfMemoryError The {@link OutOfMemoryError}. May be <code>null</code>.
         * @param npe              A {@link NullPointerException} which usually occurs when there is an underlying {@link OutOfMemoryError}. May be <code>null</code>.
         */
        void onImageCaptureOutOfMemory(OutOfMemoryError outOfMemoryError, NullPointerException npe);
    }

    public enum Result {
        IGNORED,
        PROCESSING,
        FAILED
    }

    private static class DecodeReceivedImageTaskResult {
        public final Bitmap bitmap;
        public final Uri imageLocationUri;
        public final IOException ioException;
        public final OutOfMemoryError outOfMemoryError;
        public final NullPointerException nullPointerException;

        public DecodeReceivedImageTaskResult(
                Bitmap bitmap,
                Uri imageLocationUri,
                IOException ioException,
                OutOfMemoryError outOfMemoryError,
                NullPointerException nullPointerException) {
            this.bitmap = bitmap;
            this.imageLocationUri = imageLocationUri;
            this.ioException = ioException;
            this.outOfMemoryError = outOfMemoryError;
            this.nullPointerException = nullPointerException;
        }
    }

    private class DecodeReceivedImageAsyncTask extends AsyncTask<Void, Void, DecodeReceivedImageTaskResult> {
        private final File imageTemporaryFile;
        private final Activity activity;
        private final Intent intent;
        private final ImageCapturedCallback imageCapturedCallback;
        private final BackgroundBitmapProvider backgroundBitmapProvider;

        private DecodeReceivedImageAsyncTask(
                File imageTemporaryFile,
                Activity activity,
                Intent intent,
                ImageCapturedCallback imageCapturedCallback,
                BackgroundBitmapProvider backgroundBitmapProvider) {
            this.imageTemporaryFile = imageTemporaryFile;
            this.activity = activity;
            this.intent = intent;
            this.imageCapturedCallback = imageCapturedCallback;
            this.backgroundBitmapProvider = backgroundBitmapProvider;
        }

        @Override
        protected DecodeReceivedImageTaskResult doInBackground(Void... unused) {
            Bitmap bitmap;
            Uri imageUri;
            Uri backgroundBitmapProviderImageUri;

            try {
                if (intent == null) {
                    imageUri = null;
                    backgroundBitmapProviderImageUri = Uri.fromFile(imageTemporaryFile);
                } else {
                    imageUri = intent.getData();
                    backgroundBitmapProviderImageUri = imageUri;
                }
                ContentResolver contentResolver = activity.getContentResolver();
                if (contentResolver == null) {
                    throw new IOException("Decoding image file FAILED because content resolver was null");
                }
                AssetFileDescriptor assetFileDescriptor = contentResolver.openAssetFileDescriptor(backgroundBitmapProviderImageUri, "r");
                try {
                    if (backgroundBitmapProvider == null) {
                        bitmap = BitmapFactory.decodeFileDescriptor(assetFileDescriptor.getFileDescriptor());
                    } else {
                        bitmap = backgroundBitmapProvider.provideBitmap(assetFileDescriptor);
                    }
                } finally {
                    try {
                        assetFileDescriptor.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }

                if (bitmap == null) {
                    throw new IOException("Decoding image file FAILED for unknown reason");
                }
            } catch (IOException e) {
                return new DecodeReceivedImageTaskResult(null, null, e, null, null);
            } catch (OutOfMemoryError e) {
                return new DecodeReceivedImageTaskResult(null, null, null, e, null);
            } catch (NullPointerException e) {
                // This NPE seems to be OOM related
                return new DecodeReceivedImageTaskResult(null, null, null, null, e);
            }

            return new DecodeReceivedImageTaskResult(bitmap, imageUri, null, null, null);
        }

        @Override
        protected void onPostExecute(DecodeReceivedImageTaskResult result) {
            decodeReceivedImageAsyncTask = null;
            if (!isCancelled()) {
                if (result.bitmap != null) {
                    File imageFile = imageTemporaryFile.exists() ? imageTemporaryFile : null;
                    imageCapturedCallback.onImageCaptured(result.bitmap, result.imageLocationUri, imageFile);
                } else if (result.ioException != null) {
                    imageCapturedCallback.onImageCaptureFailed(result.ioException);
                } else {
                    imageCapturedCallback.onImageCaptureOutOfMemory(result.outOfMemoryError, result.nullPointerException);
                }
            }
        }
    }
}
