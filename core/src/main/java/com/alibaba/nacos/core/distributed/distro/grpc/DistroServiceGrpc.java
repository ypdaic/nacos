package com.alibaba.nacos.core.distributed.distro.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.24.0)",
    comments = "Source: Distro.proto")
public final class DistroServiceGrpc {

  private DistroServiceGrpc() {}

  public static final String SERVICE_NAME = "DistroService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.alibaba.nacos.core.distributed.distro.grpc.Value,
      com.alibaba.nacos.core.distributed.distro.grpc.Response> getOnSyncMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "onSync",
      requestType = com.alibaba.nacos.core.distributed.distro.grpc.Value.class,
      responseType = com.alibaba.nacos.core.distributed.distro.grpc.Response.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.alibaba.nacos.core.distributed.distro.grpc.Value,
      com.alibaba.nacos.core.distributed.distro.grpc.Response> getOnSyncMethod() {
    io.grpc.MethodDescriptor<com.alibaba.nacos.core.distributed.distro.grpc.Value, com.alibaba.nacos.core.distributed.distro.grpc.Response> getOnSyncMethod;
    if ((getOnSyncMethod = DistroServiceGrpc.getOnSyncMethod) == null) {
      synchronized (DistroServiceGrpc.class) {
        if ((getOnSyncMethod = DistroServiceGrpc.getOnSyncMethod) == null) {
          DistroServiceGrpc.getOnSyncMethod = getOnSyncMethod =
              io.grpc.MethodDescriptor.<com.alibaba.nacos.core.distributed.distro.grpc.Value, com.alibaba.nacos.core.distributed.distro.grpc.Response>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "onSync"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.alibaba.nacos.core.distributed.distro.grpc.Value.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.alibaba.nacos.core.distributed.distro.grpc.Response.getDefaultInstance()))
              .setSchemaDescriptor(new DistroServiceMethodDescriptorSupplier("onSync"))
              .build();
        }
      }
    }
    return getOnSyncMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.alibaba.nacos.core.distributed.distro.grpc.Checksum,
      com.alibaba.nacos.core.distributed.distro.grpc.Response> getSyncCheckSumMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "syncCheckSum",
      requestType = com.alibaba.nacos.core.distributed.distro.grpc.Checksum.class,
      responseType = com.alibaba.nacos.core.distributed.distro.grpc.Response.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.alibaba.nacos.core.distributed.distro.grpc.Checksum,
      com.alibaba.nacos.core.distributed.distro.grpc.Response> getSyncCheckSumMethod() {
    io.grpc.MethodDescriptor<com.alibaba.nacos.core.distributed.distro.grpc.Checksum, com.alibaba.nacos.core.distributed.distro.grpc.Response> getSyncCheckSumMethod;
    if ((getSyncCheckSumMethod = DistroServiceGrpc.getSyncCheckSumMethod) == null) {
      synchronized (DistroServiceGrpc.class) {
        if ((getSyncCheckSumMethod = DistroServiceGrpc.getSyncCheckSumMethod) == null) {
          DistroServiceGrpc.getSyncCheckSumMethod = getSyncCheckSumMethod =
              io.grpc.MethodDescriptor.<com.alibaba.nacos.core.distributed.distro.grpc.Checksum, com.alibaba.nacos.core.distributed.distro.grpc.Response>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "syncCheckSum"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.alibaba.nacos.core.distributed.distro.grpc.Checksum.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.alibaba.nacos.core.distributed.distro.grpc.Response.getDefaultInstance()))
              .setSchemaDescriptor(new DistroServiceMethodDescriptorSupplier("syncCheckSum"))
              .build();
        }
      }
    }
    return getSyncCheckSumMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.alibaba.nacos.core.distributed.distro.grpc.Request,
      com.alibaba.nacos.core.distributed.distro.grpc.Response> getAcquireMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "acquire",
      requestType = com.alibaba.nacos.core.distributed.distro.grpc.Request.class,
      responseType = com.alibaba.nacos.core.distributed.distro.grpc.Response.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.alibaba.nacos.core.distributed.distro.grpc.Request,
      com.alibaba.nacos.core.distributed.distro.grpc.Response> getAcquireMethod() {
    io.grpc.MethodDescriptor<com.alibaba.nacos.core.distributed.distro.grpc.Request, com.alibaba.nacos.core.distributed.distro.grpc.Response> getAcquireMethod;
    if ((getAcquireMethod = DistroServiceGrpc.getAcquireMethod) == null) {
      synchronized (DistroServiceGrpc.class) {
        if ((getAcquireMethod = DistroServiceGrpc.getAcquireMethod) == null) {
          DistroServiceGrpc.getAcquireMethod = getAcquireMethod =
              io.grpc.MethodDescriptor.<com.alibaba.nacos.core.distributed.distro.grpc.Request, com.alibaba.nacos.core.distributed.distro.grpc.Response>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "acquire"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.alibaba.nacos.core.distributed.distro.grpc.Request.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.alibaba.nacos.core.distributed.distro.grpc.Response.getDefaultInstance()))
              .setSchemaDescriptor(new DistroServiceMethodDescriptorSupplier("acquire"))
              .build();
        }
      }
    }
    return getAcquireMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static DistroServiceStub newStub(io.grpc.Channel channel) {
    return new DistroServiceStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static DistroServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new DistroServiceBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static DistroServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new DistroServiceFutureStub(channel);
  }

  /**
   */
  public static abstract class DistroServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Value> onSync(
        io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Response> responseObserver) {
      return asyncUnimplementedStreamingCall(getOnSyncMethod(), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Checksum> syncCheckSum(
        io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Response> responseObserver) {
      return asyncUnimplementedStreamingCall(getSyncCheckSumMethod(), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Request> acquire(
        io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Response> responseObserver) {
      return asyncUnimplementedStreamingCall(getAcquireMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getOnSyncMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                com.alibaba.nacos.core.distributed.distro.grpc.Value,
                com.alibaba.nacos.core.distributed.distro.grpc.Response>(
                  this, METHODID_ON_SYNC)))
          .addMethod(
            getSyncCheckSumMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                com.alibaba.nacos.core.distributed.distro.grpc.Checksum,
                com.alibaba.nacos.core.distributed.distro.grpc.Response>(
                  this, METHODID_SYNC_CHECK_SUM)))
          .addMethod(
            getAcquireMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                com.alibaba.nacos.core.distributed.distro.grpc.Request,
                com.alibaba.nacos.core.distributed.distro.grpc.Response>(
                  this, METHODID_ACQUIRE)))
          .build();
    }
  }

  /**
   */
  public static final class DistroServiceStub extends io.grpc.stub.AbstractStub<DistroServiceStub> {
    private DistroServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private DistroServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DistroServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new DistroServiceStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Value> onSync(
        io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Response> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getOnSyncMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Checksum> syncCheckSum(
        io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Response> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getSyncCheckSumMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Request> acquire(
        io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Response> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getAcquireMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   */
  public static final class DistroServiceBlockingStub extends io.grpc.stub.AbstractStub<DistroServiceBlockingStub> {
    private DistroServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private DistroServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DistroServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new DistroServiceBlockingStub(channel, callOptions);
    }
  }

  /**
   */
  public static final class DistroServiceFutureStub extends io.grpc.stub.AbstractStub<DistroServiceFutureStub> {
    private DistroServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private DistroServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DistroServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new DistroServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_ON_SYNC = 0;
  private static final int METHODID_SYNC_CHECK_SUM = 1;
  private static final int METHODID_ACQUIRE = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final DistroServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(DistroServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_ON_SYNC:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.onSync(
              (io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Response>) responseObserver);
        case METHODID_SYNC_CHECK_SUM:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.syncCheckSum(
              (io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Response>) responseObserver);
        case METHODID_ACQUIRE:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.acquire(
              (io.grpc.stub.StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Response>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class DistroServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    DistroServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.alibaba.nacos.core.distributed.distro.grpc.Distro.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("DistroService");
    }
  }

  private static final class DistroServiceFileDescriptorSupplier
      extends DistroServiceBaseDescriptorSupplier {
    DistroServiceFileDescriptorSupplier() {}
  }

  private static final class DistroServiceMethodDescriptorSupplier
      extends DistroServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    DistroServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (DistroServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new DistroServiceFileDescriptorSupplier())
              .addMethod(getOnSyncMethod())
              .addMethod(getSyncCheckSumMethod())
              .addMethod(getAcquireMethod())
              .build();
        }
      }
    }
    return result;
  }
}
