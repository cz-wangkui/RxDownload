package zlc.season.rxdownload2.function;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

import io.reactivex.FlowableEmitter;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.exceptions.CompositeException;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import okhttp3.ResponseBody;
import retrofit2.Response;
import retrofit2.Retrofit;
import zlc.season.rxdownload2.entity.DownloadRange;
import zlc.season.rxdownload2.entity.DownloadStatus;
import zlc.season.rxdownload2.entity.DownloadType;
import zlc.season.rxdownload2.entity.DownloadTypeFactory;

import static zlc.season.rxdownload2.function.Constant.CONTEXT_NULL_HINT;
import static zlc.season.rxdownload2.function.Constant.DOWNLOAD_RECORD_FILE_DAMAGED;
import static zlc.season.rxdownload2.function.Constant.DOWNLOAD_URL_EXISTS;
import static zlc.season.rxdownload2.function.Constant.TEST_RANGE_SUPPORT;
import static zlc.season.rxdownload2.function.Utils.contentLength;
import static zlc.season.rxdownload2.function.Utils.lastModify;
import static zlc.season.rxdownload2.function.Utils.log;

/**
 * Author: Season(ssseasonnn@gmail.com)
 * Date: 2016/11/2
 * Time: 09:39
 * Download helper 类
 */
public class DownloadHelper {

    private int MAX_RETRY_COUNT = 3;

    private DownloadApi mDownloadApi;
    private FileHelper mFileHelper;
    private DownloadTypeFactory mFactory;

    //Record : { "url" : new String[] { "file path" , "temp file path" , "last modify file path" }}
    private Map<String, String[]> mDownloadRecord;

    public DownloadHelper() {
        mDownloadRecord = new HashMap<>();
        mFileHelper = new FileHelper();
        mDownloadApi = RetrofitProvider.getInstance().create(DownloadApi.class);
        mFactory = new DownloadTypeFactory(this);
    }

    public void setRetrofit(Retrofit retrofit) {
        mDownloadApi = retrofit.create(DownloadApi.class);
    }

    public void setDefaultSavePath(String defaultSavePath) {
        mFileHelper.setDefaultSavePath(defaultSavePath);
    }

    public void setMaxRetryCount(int MAX_RETRY_COUNT) {
        this.MAX_RETRY_COUNT = MAX_RETRY_COUNT;
    }

    public String[] getFileSavePaths(String savePath) {
        return mFileHelper.getRealDirectoryPaths(savePath);
    }

    public String[] getRealFilePaths(String saveName, String savePath) {
        return mFileHelper.getRealFilePaths(saveName, savePath);
    }

    public DownloadApi getDownloadApi() {
        return mDownloadApi;
    }

    public int getMaxThreads() {
        return mFileHelper.getMaxThreads();
    }

    public void setMaxThreads(int MAX_THREADS) {
        mFileHelper.setMaxThreads(MAX_THREADS);
    }

    public void prepareNormalDownload(String url, long fileLength, String lastModify)
            throws IOException, ParseException {
        mFileHelper.prepareDownload(getLastModifyFile(url), getFile(url), fileLength, lastModify);
    }

    public void saveNormalFile(FlowableEmitter<DownloadStatus> emitter,
            String url, Response<ResponseBody> resp) {
        mFileHelper.saveFile(emitter, getFile(url), resp);
    }

    public DownloadRange readDownloadRange(String url, int i) throws IOException {
        return mFileHelper.readDownloadRange(getTempFile(url), i);
    }

    public void prepareMultiThreadDownload(String url, long fileLength, String lastModify)
            throws IOException, ParseException {
        mFileHelper.prepareDownload(getLastModifyFile(url), getTempFile(url), getFile(url),
                fileLength, lastModify
        );
    }

    public void saveRangeFile(FlowableEmitter<DownloadStatus> emitter,
            int i, long start, long end, String url, ResponseBody response) {
        mFileHelper.saveFile(emitter, i, start, end, getTempFile(url), getFile(url), response);
    }

    public Observable<DownloadStatus> downloadDispatcher(final String url, final String saveName,
            final String savePath, final Context context, final boolean autoInstall) {
        try {
            beforeDownload(url, saveName, savePath);
        } catch (Exception e) {
            return Observable.error(e);
        }
        return getDownloadType(url)
                .flatMap(new Function<DownloadType, ObservableSource<DownloadStatus>>() {
                    @Override
                    public ObservableSource<DownloadStatus> apply(DownloadType downloadType)
                            throws Exception {
                        downloadType.prepareDownload();
                        return downloadType.startDownload();
                    }
                })
                .doOnComplete(new Action() {
                    @Override
                    public void run() throws Exception {
                        autoInstall(autoInstall, context, saveName, savePath);
                    }
                })
                .doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        logThrowable(throwable);
                    }
                })
                .doFinally(new Action() {
                    @Override
                    public void run() throws Exception {
                        deleteDownloadRecord(url);
                    }
                });
    }

    public Observable<DownloadType> requestHeaderWithIfRangeByGet(final String url)
            throws IOException {
        return mDownloadApi
                .GET_withIfRange(Constant.TEST_RANGE_SUPPORT, getLastModify(url), url)
                .map(new Function<Response<Void>, DownloadType>() {
                    @Override
                    public DownloadType apply(Response<Void> response)
                            throws Exception {
                        if (Utils.serverFileNotChange(response)) {
                            return getWhenServerFileNotChange(response, url);
                        } else if (Utils.serverFileChanged(response)) {
                            return getWhenServerFileChanged(response, url);
                        } else {
                            throw new RuntimeException("unknown error");
                        }
                    }
                })
                .compose(Utils.<DownloadType>retry(MAX_RETRY_COUNT));
    }

    private void logThrowable(Throwable throwable) {
        if (throwable instanceof CompositeException) {
            log(throwable.getMessage());
        } else {
            log(throwable);
        }
    }

    private void autoInstall(boolean autoInstall, Context context,
            String saveName, String savePath) {
        if (autoInstall) {
            if (context == null) {
                throw new IllegalStateException(CONTEXT_NULL_HINT);
            }
            Utils.installApk(context, new File(getRealFilePaths(saveName, savePath)[0]));
        }
    }

    private void beforeDownload(String url, String saveName, String savePath)
            throws Exception {
        if (isRecordExists(url)) {
            throw new Exception(DOWNLOAD_URL_EXISTS);
        }
        addDownloadRecord(url, saveName, savePath);
    }

    private void addDownloadRecord(String url, String saveName, String savePath)
            throws IOException {
        mFileHelper.createDirectories(savePath);
        mDownloadRecord.put(url, getRealFilePaths(saveName, savePath));
    }

    private boolean isRecordExists(String url) {
        return mDownloadRecord.get(url) != null;
    }

    private void deleteDownloadRecord(String url) {
        mDownloadRecord.remove(url);
    }

    private String getLastModify(String url) throws IOException {
        return mFileHelper.getLastModify(getLastModifyFile(url));
    }

    private boolean downloadNotComplete(String url) throws IOException {
        return mFileHelper.downloadNotComplete(getTempFile(url));
    }

    private boolean downloadNotComplete(String url, long contentLength) {
        return getFile(url).length() != contentLength;
    }

    private boolean needReDownload(String url, long contentLength) throws IOException {
        return tempFileNotExists(url) || tempFileDamaged(url, contentLength);
    }

    private boolean downloadFileExists(String url) {
        return getFile(url).exists();
    }

    private boolean tempFileDamaged(String url, long fileLength) throws IOException {
        return mFileHelper.tempFileDamaged(getTempFile(url), fileLength);
    }

    private boolean tempFileNotExists(String url) {
        return !getTempFile(url).exists();
    }

    private File getFile(String url) {
        return new File(mDownloadRecord.get(url)[0]);
    }

    private File getTempFile(String url) {
        return new File(mDownloadRecord.get(url)[1]);
    }

    private File getLastModifyFile(String url) {
        return new File(mDownloadRecord.get(url)[2]);
    }

    private Observable<DownloadType> getDownloadType(String url) {
        if (downloadFileExists(url)) {
            try {
                return getWhenFileExists(url);
            } catch (IOException e) {
                return getWhenFileNotExists(url);
            }
        } else {
            return getWhenFileNotExists(url);
        }
    }

    private Observable<DownloadType> getWhenFileNotExists(final String url) {
        return mDownloadApi
                .HEAD(TEST_RANGE_SUPPORT, url)
                .map(new Function<Response<Void>, DownloadType>() {
                    @Override
                    public DownloadType apply(Response<Void> response) throws Exception {
                        if (Utils.notSupportRange(response)) {
                            return mFactory
                                    .normal(url, contentLength(response),
                                            lastModify(response));
                        } else {
                            return mFactory
                                    .multithread(url, contentLength(response),
                                            lastModify(response));
                        }
                    }
                })
                .compose(Utils.<DownloadType>retry(MAX_RETRY_COUNT));
    }

    private Observable<DownloadType> getWhenFileExists(final String url)
            throws IOException {
        return mDownloadApi
                .HEAD_withIfRange(TEST_RANGE_SUPPORT, getLastModify(url), url)
                .map(new Function<Response<Void>, DownloadType>() {
                    @Override
                    public DownloadType apply(Response<Void> resp) throws Exception {
                        if (Utils.serverFileNotChange(resp)) {
                            return getWhenServerFileNotChange(resp, url);
                        } else if (Utils.serverFileChanged(resp)) {
                            return getWhenServerFileChanged(resp, url);
                        } else if (Utils.requestRangeNotSatisfiable(resp)) {
                            return mFactory.needGET(url, contentLength(resp), lastModify(resp));
                        } else {
                            throw new RuntimeException("unknown error");
                        }
                    }
                })
                .compose(Utils.<DownloadType>retry(MAX_RETRY_COUNT));
    }

    private DownloadType getWhenServerFileChanged(Response<Void> resp, String url) {
        if (Utils.notSupportRange(resp)) {
            return mFactory.normal(url, contentLength(resp), lastModify(resp));
        } else {
            return mFactory.multithread(url, contentLength(resp), lastModify(resp));
        }
    }

    private DownloadType getWhenServerFileNotChange(Response<Void> resp, String url) {
        if (Utils.notSupportRange(resp)) {
            return getWhenNotSupportRange(resp, url);
        } else {
            return getWhenSupportRange(resp, url);
        }
    }

    private DownloadType getWhenSupportRange(Response<Void> resp, String url) {
        long contentLength = contentLength(resp);
        try {
            if (needReDownload(url, contentLength)) {
                return mFactory.multithread(url, contentLength(resp), lastModify(resp));
            }
            if (downloadNotComplete(url)) {
                return mFactory.continued(url, contentLength, lastModify(resp));
            }
        } catch (IOException e) {
            log(DOWNLOAD_RECORD_FILE_DAMAGED);
            return mFactory.multithread(url, contentLength(resp), lastModify(resp));
        }
        return mFactory.already(contentLength);
    }

    private DownloadType getWhenNotSupportRange(Response<Void> resp, String url) {
        long contentLength = contentLength(resp);
        if (downloadNotComplete(url, contentLength)) {
            return mFactory.normal(url, contentLength(resp), lastModify(resp));
        } else {
            return mFactory.already(contentLength);
        }
    }
}
