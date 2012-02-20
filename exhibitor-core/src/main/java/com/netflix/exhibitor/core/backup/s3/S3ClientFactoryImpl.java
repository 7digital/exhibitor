package com.netflix.exhibitor.core.backup.s3;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;

public class S3ClientFactoryImpl implements S3ClientFactory
{
    @Override
    public S3Client makeNewClient(AWSCredentials credentials) throws Exception
    {
        final AmazonS3Client        client = new AmazonS3Client(credentials);
        return new S3Client()
        {
            @Override
            public InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest request) throws Exception
            {
                return client.initiateMultipartUpload(request);
            }

            @Override
            public S3Object getObject(String bucket, String key) throws Exception
            {
                return client.getObject(bucket, key);
            }

            @Override
            public ObjectListing listObjects(ListObjectsRequest request) throws Exception
            {
                return client.listObjects(request);
            }

            @Override
            public void deleteObject(String bucket, String key) throws Exception
            {
                client.deleteObject(bucket, key);
            }

            @Override
            public UploadPartResult uploadPart(UploadPartRequest request) throws Exception
            {
                return client.uploadPart(request);
            }

            @Override
            public void completeMultipartUpload(CompleteMultipartUploadRequest request) throws Exception
            {
                client.completeMultipartUpload(request);
            }

            @Override
            public void abortMultipartUpload(AbortMultipartUploadRequest request) throws Exception
            {
                client.abortMultipartUpload(request);
            }
        };
    }
}
