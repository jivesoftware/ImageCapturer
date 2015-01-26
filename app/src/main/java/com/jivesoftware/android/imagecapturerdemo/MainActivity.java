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
package com.jivesoftware.android.imagecapturerdemo;

import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.jivesoftware.android.imagecapturer.ImageCapturer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;


public class MainActivity extends ActionBarActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private ProgressBar progressBar;
    private ImageView imageView;
    private Button captureImageButton;

    private Bitmap bitmap;
    private volatile int smallestNonZeroImageViewDimension;
    private ViewTreeObserver.OnGlobalLayoutListener imageViewOnGlobalLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            if (imageView.getViewTreeObserver().isAlive()) {
                int width = imageView.getWidth();
                int height = imageView.getHeight();
                int smallestDimension = Math.min(width, height);
                if ((smallestDimension > 0) &&
                        ((smallestDimension < smallestNonZeroImageViewDimension) ||
                                (smallestNonZeroImageViewDimension == 0))) {
                    smallestNonZeroImageViewDimension = smallestDimension;
                }
            }
        }
    };

    private ImageCapturer.ImageCapturedCallback imageCapturedCallback = new ImageCapturer.ImageCapturedCallback() {
        @Override
        public void onImageCaptured(Bitmap bitmap, Uri imageUri, File imageFile) {
            setCapturing(false);

            if (imageFile != null) {
                if (!imageFile.delete()) {
                    Log.w(TAG, "Failed to delete temporary file: " + imageFile);
                }
            }

            MainActivity.this.bitmap = bitmap;
            imageView.setImageBitmap(bitmap);
        }

        @Override
        public void onImageCaptureFailed(IOException e) {
            setCapturing(false);

            Log.e(TAG, "Failed", e);
            showErrorToast(R.string.image_capture_failed_io);
        }

        @Override
        public void onImageCaptureOutOfMemory(OutOfMemoryError outOfMemoryError, NullPointerException npe) {
            setCapturing(false);

            Throwable throwable;
            if (outOfMemoryError != null) {
                throwable = outOfMemoryError;
            } else {
                throwable = npe;
            }
            Log.e(TAG, "Failed", throwable);
            showErrorToast(R.string.image_capture_failed_memory);
        }
    };

    private ImageCapturer.BackgroundBitmapProvider backgroundBitmapProvider = new ImageCapturer.BackgroundBitmapProvider() {
        @Override
        public Bitmap provideBitmap(AssetFileDescriptor imageAssetFileDescriptor) throws IOException {
            FileDescriptor imageFileDescriptor = imageAssetFileDescriptor.getFileDescriptor();
            BitmapFactory.Options samplingOpts = getSamplingOptsForFileDescriptor(imageFileDescriptor);
            BitmapFactory.Options decodeOpts = getDecodeOptsToFitMaxDimension(samplingOpts, smallestNonZeroImageViewDimension);
            return BitmapFactory.decodeFileDescriptor(imageFileDescriptor, null, decodeOpts);
        }

        private BitmapFactory.Options getSamplingOptsForFileDescriptor(FileDescriptor fd) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd, null, opts);
            return opts;
        }

        private BitmapFactory.Options getDecodeOptsToFitMaxDimension(BitmapFactory.Options samplingOpts, int maxDimension) {
            int bitmapWidth = samplingOpts.outWidth;
            int bitmapHeight = samplingOpts.outHeight;
            int scale = calculateInSampleSize(maxDimension, bitmapWidth, bitmapHeight);

            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = scale;
            return decodeOpts;
        }

        private int calculateInSampleSize(int maxDimension, int bitmapWidth, int bitmapHeight) {
            double scaleFactor = Math.max(bitmapWidth / (maxDimension * 1.0), bitmapHeight / (maxDimension * 1.0));

            int newWidth = (int) (bitmapWidth / scaleFactor);
            int newHeight = (int) (bitmapHeight / scaleFactor);

            int scale = 1;
            while (bitmapWidth / scale > newWidth || bitmapHeight / scale > newHeight) {
                scale *= 2;
            }
            return scale;
        }
    };

    private ImageCapturer imageCapturer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        imageView = (ImageView) findViewById(R.id.imageView);
        imageView.getViewTreeObserver().addOnGlobalLayoutListener(imageViewOnGlobalLayoutListener);
        captureImageButton = (Button) findViewById(R.id.captureImageButton);
        captureImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    setCapturing(true);
                    imageCapturer.awaitImageCapture(MainActivity.this, 1);
                } catch (IOException e) {
                    Log.e(TAG, "Setup failed", e);
                    showErrorToast(R.string.image_capture_setup_failed);
                }
            }
        });

        if (savedInstanceState != null) {
            imageCapturer = savedInstanceState.getParcelable("imageCapturer");
            smallestNonZeroImageViewDimension = savedInstanceState.getInt("smallestNonZeroImageViewDimension");
        }

        if (imageCapturer == null) {
            imageCapturer = new ImageCapturer();
            imageCapturer.setImageSourceChooserTitle(getString(R.string.image_source_chooser_title));
        }

        setCapturing(imageCapturer.isCapturing());
    }

    @Override
    protected void onDestroy() {
        imageCapturer.cancelBackgroundProcessing();

        // don't call imageCapturer.cancelAwaitImageCapture() without caution.
        // during a normal call, Android might destroy this Activity to free resources
        // for the image chooser Activity.

        super.onDestroy();
    }

    private void setCapturing(boolean capturing) {
        if (capturing) {
            bitmap = null;
            progressBar.setVisibility(View.VISIBLE);
            imageView.setImageBitmap(null);
            captureImageButton.setEnabled(false);
        } else {
            progressBar.setVisibility(View.GONE);
            captureImageButton.setEnabled(true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable("bitmap", bitmap);
        outState.putParcelable("imageCapturer", imageCapturer);
        outState.putInt("smallestNonZeroImageViewDimension", smallestNonZeroImageViewDimension);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        this.bitmap = savedInstanceState.getParcelable("bitmap");
        imageView.setImageBitmap(bitmap);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        ImageCapturer.Result result = imageCapturer.onActivityResult(this, requestCode, resultCode, data, imageCapturedCallback, backgroundBitmapProvider);
        switch (result) {
            case IGNORED:
                super.onActivityResult(requestCode, resultCode, data);
                break;
            case PROCESSING:
                // will be handled in the ImageCapturedCallback
                break;
            case FAILED:
                setCapturing(false);
                showErrorToast(getString(R.string.image_capture_failed_result_format, resultCode));
                break;
        }
    }

    private void showErrorToast(int resourceId) {
        Toast.makeText(this, resourceId, Toast.LENGTH_SHORT).show();
    }

    private void showErrorToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
