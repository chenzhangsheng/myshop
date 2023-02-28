package cn.lili.modules.file.plugin.impl;

import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.modules.file.entity.enums.OssEnum;
import cn.lili.modules.file.plugin.FilePlugin;
import cn.lili.modules.system.entity.dto.OssSetting;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.*;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.io.InputStream;
import java.util.List;
/**
 * google oss 文件操作
 *
 * @author chuck
 */
@Slf4j
public class GooglePlugin implements FilePlugin {

    private OssSetting ossSetting;

    public GooglePlugin(OssSetting ossSetting){
        this.ossSetting = ossSetting;
    }

    private Storage getGoogleOSSClient() {
        try {
            String JSON_KEY = ossSetting.getGoogleOSSPrivateKey();
            StorageOptions options = StorageOptions.newBuilder().setProjectId(ossSetting.getGoogleOSSProjectId())
                    .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(JSON_KEY.getBytes()))).build();
            return options.getService();
        } catch (IOException ex) {
            log.error("init google OSSException Error Message:" + ex.getMessage());
            throw new ServiceException(ResultCode.OSS_NOT_EXIST);
        }
    }


    @Override
    public OssEnum pluginName() {
        return OssEnum.GOOGLE_OSS;
    }

    @Override
    public String pathUpload(String filePath, String key) {
        Storage storage = getGoogleOSSClient();
        try {
            BlobId blobId = BlobId.of(ossSetting.getGoogleOSSBucketName(), key);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
            Blob blob1 = storage.createFrom(blobInfo, Paths.get(filePath));
            return blob1.getMediaLink();
        } catch (StorageException oe) {
            log.error("Caught an OSSException, which means your request made it to OSS, "
                    + "but was rejected with an error response for some reason.");
            log.error("Error Message: " + oe.getMessage());
            log.error("Error Code:       " + oe.getMessage());
            throw new ServiceException(ResultCode.OSS_DELETE_ERROR);
        } catch (Exception ce) {
            log.error("Caught an ClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with OSS, "
                    + "such as not being able to access the network.");
            log.error("Error Message: " + ce.getMessage());
            throw new ServiceException(ResultCode.OSS_DELETE_ERROR);
        }
    }

    @Override
    public String inputStreamUpload(InputStream inputStream, String key) {
        Storage storage = getGoogleOSSClient();
        try {
            BlobId blobId = BlobId.of(ossSetting.getGoogleOSSBucketName(), key);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
            Blob blob1 = storage.createFrom(blobInfo, inputStream);
            return blob1.toString();
        } catch (StorageException oe) {
            log.error("Caught an OSSException, which means your request made it to OSS, "
                    + "but was rejected with an error response for some reason.");
            log.error("Error Message: " + oe.getMessage());
            log.error("Error Code:       " + oe.getMessage());
            throw new ServiceException(ResultCode.OSS_DELETE_ERROR);
        } catch (Exception ce) {
            log.error("Caught an ClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with OSS, "
                    + "such as not being able to access the network.");
            log.error("Error Message: " + ce.getMessage());
            throw new ServiceException(ResultCode.OSS_DELETE_ERROR);
        }
    }

    @Override
    public void deleteFile(List<String> keys) {
        Storage storage = getGoogleOSSClient();
        try {
            for (String key : keys) {
                Blob blob = storage.get(ossSetting.getGoogleOSSBucketName(), key);
                if (blob == null) {
                    log.info("The object " + key + " wasn't found in " + ossSetting.getGoogleOSSBucketName());
                    continue;
                }
                // Optional: set a generation-match precondition to avoid potential race
                // conditions and data corruptions. The request to upload returns a 412 error if
                // the object's generation number does not match your precondition.
                Storage.BlobSourceOption precondition =
                        Storage.BlobSourceOption.generationMatch(blob.getGeneration());
                storage.delete(ossSetting.getGoogleOSSBucketName(), key, precondition);
            }
        } catch (StorageException oe) {
            log.error("Caught an OSSException, which means your request made it to OSS, "
                    + "but was rejected with an error response for some reason.");
            log.error("Error Message: " + oe.getMessage());
            log.error("Error Code:       " + oe.getMessage());
            throw new ServiceException(ResultCode.OSS_DELETE_ERROR);
        } catch (Exception ce) {
            log.error("Caught an ClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with OSS, "
                    + "such as not being able to access the network.");
            log.error("Error Message: " + ce.getMessage());
            throw new ServiceException(ResultCode.OSS_DELETE_ERROR);
        }
    }
}
