package com.pubnub.api.endpoints.files;

import com.pubnub.api.PubNub;
import com.pubnub.api.PubNubException;
import com.pubnub.api.builder.PubNubErrorBuilder;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.endpoints.remoteaction.RemoteAction;
import com.pubnub.api.enums.PNOperationType;
import com.pubnub.api.enums.PNStatusCategory;
import com.pubnub.api.managers.RetrofitManager;
import com.pubnub.api.models.consumer.PNErrorData;
import com.pubnub.api.models.consumer.PNStatus;
import com.pubnub.api.models.server.files.FileUploadRequestDetails;
import com.pubnub.api.models.server.files.FormField;
import com.pubnub.api.services.S3Service;
import com.pubnub.api.vendor.FileEncryptionUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import retrofit2.Call;
import retrofit2.Response;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

import static com.pubnub.api.vendor.FileEncryptionUtil.effectiveCipherKey;
import static com.pubnub.api.vendor.FileEncryptionUtil.loadFromInputStream;

@Slf4j
class UploadFile implements RemoteAction<Void> {
    private static final MediaType APPLICATION_OCTET_STREAM = MediaType.get("application/octet-stream");
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String FILE_PART_MULTIPART = "file";
    private final S3Service s3Service;
    private final String fileName;
    private final InputStream inputStream;
    private final String cipherKey;
    private final FormField key;
    private final List<FormField> formParams;
    private final String baseUrl;
    private Call<Void> call;

    UploadFile(S3Service s3Service,
               String fileName,
               InputStream inputStream,
               String cipherKey,
               FormField key,
               List<FormField> formParams,
               String baseUrl) {
        this.s3Service = s3Service;
        this.fileName = fileName;
        this.inputStream = inputStream;
        this.cipherKey = cipherKey;
        this.key = key;
        this.formParams = formParams;
        this.baseUrl = baseUrl;
    }

    private static void addFormParamsWithKeyFirst(FormField keyValue,
                                                  List<FormField> formParams,
                                                  MultipartBody.Builder builder) {
        builder.addFormDataPart(keyValue.getKey(), keyValue.getValue());
        for (FormField it : formParams) {
            if (!it.getKey().equals(keyValue.getKey())) {
                builder.addFormDataPart(it.getKey(), it.getValue());
            }
        }
    }

    private Call<Void> prepareCall() throws PubNubException, IOException {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        addFormParamsWithKeyFirst(key, formParams, builder);
        MediaType mediaType = getMediaType(getContentType(formParams));

        RequestBody requestBody;
        if (cipherKey == null) {
            requestBody = RequestBody.create(mediaType, loadFromInputStream(inputStream));
        } else {
            requestBody = RequestBody.create(mediaType, FileEncryptionUtil.encryptToBytes(cipherKey, inputStream));
        }

        builder.addFormDataPart(FILE_PART_MULTIPART, fileName, requestBody);
        return s3Service.upload(baseUrl, builder.build());
    }

    @Nullable
    private String getContentType(List<FormField> formFields) {
        String contentType = null;
        for (FormField field : formFields) {
            if (field.getKey().equalsIgnoreCase(CONTENT_TYPE_HEADER)) {
                contentType = field.getValue();
                break;
            }
        }
        return contentType;
    }

    private MediaType getMediaType(@Nullable String contentType) {
        if (contentType == null) {
            return APPLICATION_OCTET_STREAM;
        }

        try {
            return MediaType.get(contentType);
        }  catch (Throwable t) {
            log.warn("Content-Type: " + contentType + " was not recognized by MediaType.get", t);
            return APPLICATION_OCTET_STREAM;
        }
    }

    @Override
    public Void sync() throws PubNubException {
        try {
            call = prepareCall();
        } catch (IOException e) {
            throw PubNubException.builder()
                    .errormsg(e.getMessage())
                    .build();
        }

        Response<Void> serverResponse;
        try {
            serverResponse = call.execute();
        } catch (IOException e) {
            throw PubNubException.builder()
                    .pubnubError(PubNubErrorBuilder.PNERROBJ_PARSING_ERROR)
                    .errormsg(e.toString())
                    .affectedCall(call)
                    .build();
        }

        if (!serverResponse.isSuccessful()) {
            throw createException(serverResponse);
        }
        return null;
    }

    @Override
    public void async(@NotNull PNCallback<Void> callback) {
        try {
            call = prepareCall();
            call.enqueue(new retrofit2.Callback<Void>() {

                @Override
                public void onResponse(@NotNull Call<Void> performedCall, @NotNull Response<Void> response) {
                    if (!response.isSuccessful()) {
                        PubNubException ex = createException(response);

                        PNStatusCategory pnStatusCategory = PNStatusCategory.PNUnknownCategory;

                        if (response.code() == HttpURLConnection.HTTP_UNAUTHORIZED
                            || response.code() == HttpURLConnection.HTTP_FORBIDDEN) {
                            pnStatusCategory = PNStatusCategory.PNAccessDeniedCategory;
                        }

                        if (response.code() == HttpURLConnection.HTTP_BAD_REQUEST) {
                            pnStatusCategory = PNStatusCategory.PNBadRequestCategory;
                        }

                        callback.onResponse(null,
                                createStatusResponse(pnStatusCategory, response, ex));
                        return;
                    }

                    callback.onResponse(null,
                            createStatusResponse(PNStatusCategory.PNAcknowledgmentCategory, response,
                                    null));
                }

                @Override
                public void onFailure(@NotNull Call<Void> performedCall, @NotNull Throwable throwable) {
                    if (call.isCanceled()) {
                        return;
                    }

                    PNStatusCategory pnStatusCategory;

                    PubNubException.PubNubExceptionBuilder pubnubException = PubNubException.builder()
                            .errormsg(throwable.getMessage());

                    try {
                        throw throwable;
                    } catch (UnknownHostException networkException) {
                        pubnubException.pubnubError(PubNubErrorBuilder.PNERROBJ_CONNECTION_NOT_SET);
                        pnStatusCategory = PNStatusCategory.PNUnexpectedDisconnectCategory;
                    } catch (SocketException | SSLException exception) {
                        pubnubException.pubnubError(PubNubErrorBuilder.PNERROBJ_CONNECT_EXCEPTION);
                        pnStatusCategory = PNStatusCategory.PNUnexpectedDisconnectCategory;
                    } catch (SocketTimeoutException socketTimeoutException) {
                        pubnubException.pubnubError(PubNubErrorBuilder.PNERROBJ_SUBSCRIBE_TIMEOUT);
                        pnStatusCategory = PNStatusCategory.PNTimeoutCategory;
                    } catch (Throwable throwable1) {
                        pubnubException.pubnubError(PubNubErrorBuilder.PNERROBJ_HTTP_ERROR);
                        if (performedCall.isCanceled()) {
                            pnStatusCategory = PNStatusCategory.PNCancelledCategory;
                        } else {
                            pnStatusCategory = PNStatusCategory.PNBadRequestCategory;
                        }
                    }

                    callback.onResponse(null, createStatusResponse(pnStatusCategory, null, pubnubException.build()));

                }
            });

        } catch (IOException | PubNubException e) {
            //FIXME which category shall this error belong to?
            callback.onResponse(null,
                    createStatusResponse(PNStatusCategory.PNUnknownCategory, null, e));
        }
    }

    @Override
    public void retry() {
    }

    @Override
    public void silentCancel() {
        if (!call.isCanceled()) {
            call.cancel();
        }
    }

    private PubNubException createException(Response<Void> response) {
        String responseBodyText;

        try {
            responseBodyText = response.errorBody().string();
        } catch (IOException e) {
            responseBodyText = "N/A";
        }

        return PubNubException.builder()
                .pubnubError(PubNubErrorBuilder.PNERROBJ_HTTP_ERROR)
                .errormsg(responseBodyText)
                .statusCode(response.code())
                .build();
    }

    private PNStatus createStatusResponse(PNStatusCategory category, Response<Void> response, Exception throwable) {
        PNStatus.PNStatusBuilder pnStatus = PNStatus.builder();

        if (response == null || throwable != null) {
            pnStatus.error(true);
        }
        if (throwable != null) {
            PNErrorData pnErrorData = new PNErrorData(throwable.getMessage(), throwable);
            pnStatus.errorData(pnErrorData);
        }

        if (response != null) {
            pnStatus.statusCode(response.code());
            pnStatus.tlsEnabled(response.raw().request().url().isHttps());
            pnStatus.origin(response.raw().request().url().host());
            pnStatus.clientRequest(response.raw().request());
        }

        pnStatus.operation(getOperationType());
        pnStatus.category(category);

        return pnStatus.build();
    }

    private PNOperationType getOperationType() {
        return PNOperationType.PNFileAction;
    }

    static class Factory {
        private final PubNub pubNub;
        private final RetrofitManager retrofitManager;

        Factory(PubNub pubNub, RetrofitManager retrofitManager) {
            this.pubNub = pubNub;
            this.retrofitManager = retrofitManager;
        }

        RemoteAction<Void> create(String fileName,
                                  InputStream inputStreamm,
                                  String cipherKey,
                                  FileUploadRequestDetails fileUploadRequestDetails) {
            String effectiveCipherKey = effectiveCipherKey(pubNub, cipherKey);

            return new UploadFile(retrofitManager.getS3Service(),
                    fileName,
                    inputStreamm,
                    effectiveCipherKey,
                    fileUploadRequestDetails.getKeyFormField(), fileUploadRequestDetails.getFormFields(),
                    fileUploadRequestDetails.getUrl());
        }
    }

}
