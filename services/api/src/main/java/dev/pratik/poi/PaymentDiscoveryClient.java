package dev.pratik.poi;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Fixed, explicitly enabled inquiry transport. Never accepts a URL from a browser. */
@Component
public class PaymentDiscoveryClient {
  static final int MAX_BYTES = 2 * 1024 * 1024;
  record Reply(int status, String type, byte[] body) {}
  interface Transport { Reply post(URI target, byte[] body, Duration timeout) throws Exception; }
  record Result(List<Map<String,String>> rows, boolean hasMore, String observedAt, ObjectNode upstream) {
    Result(List<Map<String,String>> rows,boolean hasMore,String observedAt) { this(rows,hasMore,observedAt,null); }
  }
  private final ObjectMapper mapper;
  private final String mode, deployment, wireFormat;
  private final FlexcubeInquirySupport flexcube;
  private final URI endpoint;
  private final Duration timeout;
  private final Transport transport;
  private final Semaphore requests = new Semaphore(2);

  @Autowired
  public PaymentDiscoveryClient(ObjectMapper mapper,
      @Value("${poi.payment-discovery.mode:MOCK}") String mode,
      @Value("${poi.payment-discovery.bank-enabled:false}") boolean enabled,
      @Value("${poi.payment-discovery.bank-url:}") String url,
      @Value("${poi.payment-discovery.deployment:FCR-UAT}") String deployment,
      @Value("${poi.payment-discovery.timeout-seconds:10}") int seconds,
      @Value("${poi.payment-discovery.wire-format:CANONICAL}") String wireFormat,
      @Value("${poi.payment-discovery.api-token:}") String token,
      FlexcubeInquirySupport flexcube) {
    this(mapper, mode, enabled, url, deployment, seconds, wireFormat, flexcube, httpTransport(token));
  }
  public PaymentDiscoveryClient(ObjectMapper mapper,String mode,boolean enabled,String url,String deployment,int seconds) {
    this(mapper,mode,enabled,url,deployment,seconds,httpTransport(""));
  }
  PaymentDiscoveryClient(ObjectMapper mapper, String mode, boolean enabled, String url,
      String deployment, int seconds, Transport transport) {
    this(mapper,mode,enabled,url,deployment,seconds,"CANONICAL",null,transport);
  }
  PaymentDiscoveryClient(ObjectMapper mapper,String mode,boolean enabled,String url,String deployment,int seconds,
      String wireFormat,FlexcubeInquirySupport flexcube,Transport transport) {
    if (!Set.of("MOCK","BANK_API","DISABLED").contains(mode)) throw new IllegalArgumentException("Invalid discovery mode.");
    if (!Set.of("CANONICAL","FLEXCUBE").contains(wireFormat) || wireFormat.equals("FLEXCUBE") && flexcube==null) throw new IllegalArgumentException("Invalid discovery wire format.");
    this.wireFormat=wireFormat; this.flexcube=flexcube;
    this.mapper=mapper; this.deployment=deployment; this.transport=transport;
    if (!deployment.matches("[A-Za-z0-9_.:-]{1,100}")) throw new IllegalArgumentException("Invalid source deployment.");
    this.mode=mode.equals("BANK_API") && (!enabled || url.isBlank()) ? "DISABLED" : mode;
    this.timeout=Duration.ofSeconds(Math.max(1,Math.min(30,seconds)));
    if (this.mode.equals("BANK_API")) {
      URI uri=URI.create(url);
      String host=uri.getHost();
      if (!"https".equals(uri.getScheme()) || host==null || uri.getUserInfo()!=null
          || uri.getQuery()!=null || uri.getFragment()!=null || uri.getPort()==0 || uri.getPort()>65535
          || host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.startsWith("169.254.")
          || Set.of("0.0.0.0","::1","[::1]").contains(host) || uri.getPath().isBlank())
        throw new IllegalArgumentException("Bank inquiry requires a fixed HTTPS endpoint with normal TLS validation.");
      this.endpoint=uri;
    } else this.endpoint=null;
  }
  String mode() { return mode; }
  String wireFormat() { return wireFormat; }
  String deployment(boolean synthetic) { return synthetic ? "ORIGINAL-MOCK" : deployment; }

  Result fetch(String branch, String bank, LocalDate date, int count) {
    if (mode.equals("DISABLED")) throw new ApiException(503,"DISCOVERY_DISABLED","Bank inquiry is not explicitly configured and enabled.");
    if (mode.equals("MOCK")) return mock(branch,bank,date);
    if (wireFormat.equals("FLEXCUBE")) {
      if (date==null || count<1 || count>200) throw PaymentDiscoveryService.invalid("Provide an inquiry date and record count from 1 through 200.");
      return invoke(flexcube.request("PO01",bank,branch,scope(bank,branch).put("inquiryDate",date.toString()).put("recordCount",count)));
    }
    return invoke(mapper.createObjectNode().put("orgBank",bank).put("orgBranch",branch).put("inquiryDate",date.toString()).put("recordCount",count));
  }
  Result lookup(String branch,String bank,String referenceType,String reference) {
    if (mode.equals("DISABLED")) throw new ApiException(503,"DISCOVERY_DISABLED","Bank inquiry is not explicitly configured and enabled.");
    if (mode.equals("MOCK")) return mockLookup(branch,bank,referenceType,reference);
    if (wireFormat.equals("FLEXCUBE")) {
      if (!Set.of("FCR","UTR").contains(referenceType)) throw PaymentDiscoveryService.invalid("referenceType must be FCR or UTR.");
      PaymentDiscoveryService.nativeText(reference,200,false);
      return invoke(flexcube.request("PO01",bank,branch,scope(bank,branch).put("referenceType",referenceType).put("reference",reference)));
    }
    return invoke(mapper.createObjectNode().put("orgBank",bank).put("orgBranch",branch).put("referenceType",referenceType).put("reference",reference));
  }
  private ObjectNode scope(String bank,String branch) {
    return mapper.createObjectNode().put("originatingBankCode",FlexcubeInquirySupport.code(bank)).put("originatingBranchCode",FlexcubeInquirySupport.code(branch));
  }
  private Result invoke(ObjectNode body) {
    if (!requests.tryAcquire()) throw new ApiException(503,"DISCOVERY_BUSY","Inquiry is busy; no fallback was used.");
    try {
      Reply reply=transport.post(endpoint,mapper.writeValueAsBytes(body),timeout);
      if (reply.status()!=200) throw new ApiException(502,"DISCOVERY_UPSTREAM_ERROR","The configured inquiry did not return a successful response; no fallback was used.");
      if (reply.body().length>MAX_BYTES) throw new ApiException(502,"DISCOVERY_RESPONSE_TOO_LARGE","Inquiry response exceeds its byte limit.");
      if (!reply.type().split(";",2)[0].trim().equalsIgnoreCase("application/json")) throw invalid();
      ObjectNode value=UatService.parseObject(mapper,reply.body(),invalid());
      if (wireFormat.equals("FLEXCUBE")) {
        flexcube.validateResponse(value,body);
        return FlexcubeDiscoveryAdapter.parse(mapper,value,body);
      }
      PaymentDiscoveryService.fields(value,Set.of("items","hasMore","observedAt"));
      if (value.size()!=3 || !value.path("items").isArray() || value.path("items").size()>2000 || !value.path("hasMore").isBoolean()) throw invalid();
      String observed=PaymentDiscoveryService.text(value,"observedAt",100,true);
      OffsetDateTime.parse(observed);
      List<Map<String,String>> rows=new ArrayList<>();
      for (JsonNode item:value.path("items")) {
        if (!item.isObject()) throw invalid();
        Map<String,String> row=new LinkedHashMap<>();
        var iterator=item.fields();
        while (iterator.hasNext()) {
          var field=iterator.next();
          if (!(field.getValue().isTextual() || field.getKey().equals("REF_SUBSEQ_NO") && field.getValue().isNull())) throw invalid();
          row.put(field.getKey(),field.getValue().textValue());
        }
        rows.add(row);
      }
      return new Result(rows,value.path("hasMore").asBoolean(),observed);
    } catch (ApiException exception) { if(exception.status==422)throw invalid();throw exception; }
    catch (TimeoutException | HttpTimeoutException exception) {
      throw new ApiException(504,"DISCOVERY_TIMEOUT","Inquiry timed out; no fallback or partial import was used.");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt(); throw new ApiException(503,"DISCOVERY_UNAVAILABLE","Inquiry was interrupted.");
    } catch (javax.net.ssl.SSLHandshakeException | javax.net.ssl.SSLPeerUnverifiedException exception) {
      throw new ApiException(503,"DISCOVERY_TLS_ERROR",
          "The bank HTTPS connection could not be verified. Ask an administrator to check that the configured hostname matches the server certificate and that its certificate chain is trusted.");
    } catch (java.time.format.DateTimeParseException exception) { throw invalid(); }
    catch (Exception exception) {
      throw new ApiException(503,"DISCOVERY_UNAVAILABLE","The configured inquiry could not complete; no fallback was used.");
    } finally { requests.release(); }
  }
  private static ApiException invalid() { return new ApiException(502,"INVALID_DISCOVERY_RESPONSE","Inquiry response does not match the proposed native-column contract."); }
  static Result mock(String branch,String bank,LocalDate date) {
    List<Map<String,String>> rows=new ArrayList<>();
    for (int i=1;i<=6;i++) {
      for (int sub=0;sub<(i==1?2:1);sub++) {
        Map<String,String> row=new LinkedHashMap<>();
        row.put("PIO_REF_TXN_NO","0"+date.toString().replace("-","")+branch+String.format(Locale.ROOT,"%014d",i));
        row.put("PIO_ORG_BRN",branch); row.put("PIO_ORG_BANK",bank); row.put("REF_SUBSEQ_NO",Integer.toString(sub));
        row.put("UTR_REF_NO","MOCK-"+branch+"-"+date+"-"+(i>=5?"SHARED":i));
        row.put("DATINITIATION",date.atTime(8+i,0).toString()+":00");
        row.put("NUMAMOUNT_4038",Integer.toString(100+i)+".001"); row.put("CURRENCY","INR"); row.put("DIRECTION","OUT");
        rows.add(row);
      }
    }
    return new Result(rows,false,Instant.now().toString());
  }
  static Result mockLookup(String branch,String bank,String type,String reference) {
    LocalDate date=null;
    try {
      if(type.equals("FCR") && reference.matches("[0-9]+") && reference.length()==23+branch.length()
          && reference.startsWith("0") && reference.substring(9,9+branch.length()).equals(branch))
        date=LocalDate.parse(reference.substring(1,9),java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
      String prefix="MOCK-"+branch+"-";
      if(type.equals("UTR") && reference.startsWith(prefix) && reference.length()>=prefix.length()+12 && reference.charAt(prefix.length()+10)=='-')
        date=LocalDate.parse(reference.substring(prefix.length(),prefix.length()+10));
    } catch(java.time.format.DateTimeParseException ignored) { date=null; }
    if(date==null)return new Result(List.of(),false,Instant.now().toString());
    String column=type.equals("FCR")?"PIO_REF_TXN_NO":"UTR_REF_NO";
    Result catalog=mock(branch,bank,date);
    return new Result(catalog.rows().stream().filter(row->reference.equals(row.get(column))).toList(),false,catalog.observedAt());
  }
  private static Transport httpTransport(String token) {
    if (token.length()>4096 || token.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid discovery API token.");
    HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    return (uri,body,timeout)-> {
      HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type","application/json")
          .header("Accept","application/json");
      if (!token.isBlank()) builder.header("Authorization","Bearer "+token);
      HttpRequest request=builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
      var future=client.sendAsync(request,info->new LimitedBody());
      try {
        var response=future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
        return new Reply(response.statusCode(),response.headers().firstValue("Content-Type").orElse(""),response.body());
      } catch (TimeoutException | InterruptedException exception) { future.cancel(true); throw exception; }
      catch (ExecutionException exception) {
        Throwable cause=exception.getCause();
        if (cause instanceof Exception failure) throw failure;
        throw exception;
      }
    };
  }
  static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> completed=new CompletableFuture<>(); final ByteArrayOutputStream bytes=new ByteArrayOutputStream(); Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody(){return completed;}
    public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
    public void onNext(List<ByteBuffer> buffers){for(ByteBuffer buffer:buffers){if(buffer.remaining()>MAX_BYTES-bytes.size()){subscription.cancel();completed.completeExceptionally(new ApiException(502,"DISCOVERY_RESPONSE_TOO_LARGE","Inquiry response exceeds its byte limit."));return;}byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);}subscription.request(1);}
    public void onError(Throwable error){completed.completeExceptionally(error);}
    public void onComplete(){completed.complete(bytes.toByteArray());}
  }
}
