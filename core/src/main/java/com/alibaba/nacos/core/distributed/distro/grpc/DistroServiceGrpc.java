package com.alibaba.nacos.core.distributed.distro.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
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
  private static volatile io.grpc.MethodDescriptor<Load,
      Values> getLoadMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "load",
      requestType = Load.class,
      responseType = Values.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<Load,
      Values> getLoadMethod() {
    io.grpc.MethodDescriptor<Load, Values> getLoadMethod;
    if ((getLoadMethod = DistroServiceGrpc.getLoadMethod) == null) {
      synchronized (DistroServiceGrpc.class) {
        if ((getLoadMethod = DistroServiceGrpc.getLoadMethod) == null) {
          DistroServiceGrpc.getLoadMethod = getLoadMethod =
              io.grpc.MethodDescriptor.<Load, Values>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "load"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Load.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Values.getDefaultInstance()))
              .setSchemaDescriptor(new DistroServiceMethodDescriptorSupplier("load"))
              .build();
        }
      }
    }
    return getLoadMethod;
  }

  private static volatile io.grpc.MethodDescriptor<Merge,
      Response> getSendMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "send",
      requestType = Merge.class,
      responseType = Response.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<Merge,
      Response> getSendMethod() {
    io.grpc.MethodDescriptor<Merge, Response> getSendMethod;
    if ((getSendMethod = DistroServiceGrpc.getSendMethod) == null) {
      synchronized (DistroServiceGrpc.class) {
        if ((getSendMethod = DistroServiceGrpc.getSendMethod) == null) {
          DistroServiceGrpc.getSendMethod = getSendMethod =
              io.grpc.MethodDescriptor.<Merge, Response>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "send"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Merge.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Response.getDefaultInstance()))
              .setSchemaDescriptor(new DistroServiceMethodDescriptorSupplier("send"))
              .build();
        }
      }
    }
    return getSendMethod;
  }

  private static volatile io.grpc.MethodDescriptor<Checksum,
      Response> getReceiveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "receive",
      requestType = Checksum.class,
      responseType = Response.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<Checksum,
      Response> getReceiveMethod() {
    io.grpc.MethodDescriptor<Checksum, Response> getReceiveMethod;
    if ((getReceiveMethod = DistroServiceGrpc.getReceiveMethod) == null) {
      synchronized (DistroServiceGrpc.class) {
        if ((getReceiveMethod = DistroServiceGrpc.getReceiveMethod) == null) {
          DistroServiceGrpc.getReceiveMethod = getReceiveMethod =
              io.grpc.MethodDescriptor.<Checksum, Response>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "receive"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Checksum.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Response.getDefaultInstance()))
              .setSchemaDescriptor(new DistroServiceMethodDescriptorSupplier("receive"))
              .build();
        }
      }
    }
    return getReceiveMethod;
  }

  private static volatile io.grpc.MethodDescriptor<Query,
      Values> getQueryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "query",
      requestType = Query.class,
      responseType = Values.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<Query,
      Values> getQueryMethod() {
    io.grpc.MethodDescriptor<Query, Values> getQueryMethod;
    if ((getQueryMethod = DistroServiceGrpc.getQueryMethod) == null) {
      synchronized (DistroServiceGrpc.class) {
        if ((getQueryMethod = DistroServiceGrpc.getQueryMethod) == null) {
          DistroServiceGrpc.getQueryMethod = getQueryMethod =
              io.grpc.MethodDescriptor.<Query, Values>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "query"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Query.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Values.getDefaultInstance()))
              .setSchemaDescriptor(new DistroServiceMethodDescriptorSupplier("query"))
              .build();
        }
      }
    }
    return getQueryMethod;
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
    public void load(Load request,
        io.grpc.stub.StreamObserver<Values> responseObserver) {
      asyncUnimplementedUnaryCall(getLoadMethod(), responseObserver);
    }

    /**
     */
    public void send(Merge request,
        io.grpc.stub.StreamObserver<Response> responseObserver) {
      asyncUnimplementedUnaryCall(getSendMethod(), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<Checksum> receive(
        io.grpc.stub.StreamObserver<Response> responseObserver) {
      return asyncUnimplementedStreamingCall(getReceiveMethod(), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<Query> query(
        io.grpc.stub.StreamObserver<Values> responseObserver) {
      return asyncUnimplementedStreamingCall(getQueryMethod(), responseObserver);
    }

    @Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getLoadMethod(),
            asyncServerStreamingCall(
              new MethodHandlers<
                Load,
                Values>(
                  this, METHODID_LOAD)))
          .addMethod(
            getSendMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                Merge,
                Response>(
                  this, METHODID_SEND)))
          .addMethod(
            getReceiveMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                Checksum,
                Response>(
                  this, METHODID_RECEIVE)))
          .addMethod(
            getQueryMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                Query,
                Values>(
                  this, METHODID_QUERY)))
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

    @Override
    protected DistroServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new DistroServiceStub(channel, callOptions);
    }

    /**
     */
    public void load(Load request,
        io.grpc.stub.StreamObserver<Values> responseObserver) {
      asyncServerStreamingCall(
          getChannel().newCall(getLoadMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void send(Merge request,
        io.grpc.stub.StreamObserver<Response> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getSendMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<Checksum> receive(
        io.grpc.stub.StreamObserver<Response> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getReceiveMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<Query> query(
        io.grpc.stub.StreamObserver<Values> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getQueryMethod(), getCallOptions()), responseObserver);
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

    @Override
    protected DistroServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new DistroServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public java.util.Iterator<Values> load(
        Load request) {
      return blockingServerStreamingCall(
          getChannel(), getLoadMethod(), getCallOptions(), request);
    }

    /**
     */
    public Response send(Merge request) {
      return blockingUnaryCall(
          getChannel(), getSendMethod(), getCallOptions(), request);
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

    @Override
    protected DistroServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new DistroServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<Response> send(
        Merge request) {
      return futureUnaryCall(
          getChannel().newCall(getSendMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_LOAD = 0;
  private static final int METHODID_SEND = 1;
  private static final int METHODID_RECEIVE = 2;
  private static final int METHODID_QUERY = 3;

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

    @Override
    @SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_LOAD:
          serviceImpl.load((Load) request,
              (io.grpc.stub.StreamObserver<Values>) responseObserver);
          break;
        case METHODID_SEND:
          serviceImpl.send((Merge) request,
              (io.grpc.stub.StreamObserver<Response>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_RECEIVE:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.receive(
              (io.grpc.stub.StreamObserver<Response>) responseObserver);
        case METHODID_QUERY:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.query(
              (io.grpc.stub.StreamObserver<Values>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class DistroServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    DistroServiceBaseDescriptorSupplier() {}

    @Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return Distro.getDescriptor();
    }

    @Override
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

    @Override
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
              .addMethod(getLoadMethod())
              .addMethod(getSendMethod())
              .addMethod(getReceiveMethod())
              .addMethod(getQueryMethod())
              .build();
        }
      }
    }
    return result;
  }
}
