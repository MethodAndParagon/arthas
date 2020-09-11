package com.alibaba.arthas.channel.server.web;

import com.alibaba.arthas.channel.proto.ActionRequest;
import com.alibaba.arthas.channel.proto.ActionResponse;
import com.alibaba.arthas.channel.proto.ExecuteParams;
import com.alibaba.arthas.channel.proto.ExecuteResult;
import com.alibaba.arthas.channel.proto.RequestAction;
import com.alibaba.arthas.channel.proto.ResponseStatus;
import com.alibaba.arthas.channel.proto.ResultFormat;
import com.alibaba.arthas.channel.server.api.ApiAction;
import com.alibaba.arthas.channel.server.api.ApiException;
import com.alibaba.arthas.channel.server.conf.ScheduledExecutorConfig;
import com.alibaba.arthas.channel.server.model.AgentVO;
import com.alibaba.arthas.channel.server.api.ApiRequest;
import com.alibaba.arthas.channel.server.api.ApiResponse;
import com.alibaba.arthas.channel.server.api.ApiStatus;
import com.alibaba.arthas.channel.server.service.ApiActionDelegateService;
import com.alibaba.arthas.channel.server.service.AgentManageService;
import com.alibaba.fastjson.JSON;
import com.google.protobuf.StringValue;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * @author gongdewei 2020/8/10
 */
@RestController
public class AgentController {

    private static final Logger logger = LoggerFactory.getLogger(AgentController.class);

    @Autowired
    private AgentManageService agentManageService;

    @Autowired
    private ApiActionDelegateService apiActionDelegateService;

    @Autowired
    private ScheduledExecutorConfig executorServiceConfig;


    @RequestMapping("/agents")
    public Mono<List<AgentVO>> listAgents() {
        return agentManageService.listAgents();
    }

    @RequestMapping("/agent/{agentId}")
    public AgentVO getAgent(@PathVariable String agentId) {
        AgentVO agentVO = checkAgentExists(agentId);
        return agentVO;
    }

    @RequestMapping("/agent/{agentId}/init_session")
    public Mono<ApiResponse> initSession(@PathVariable String agentId) throws Exception {
        checkAgentExists(agentId);
        try {
            return apiActionDelegateService.initSession(agentId)
                    .timeout(Duration.ofMillis(30000))
                    .map(AgentController::convertApiResponse);
        } catch (Throwable e) {
            logger.error("create session failure: " + e.toString(), e);
            return Mono.just(ActionResponse.newBuilder()
                    .setAgentId(agentId)
                    .setStatus(ResponseStatus.FAILED)
                    .setMessage(StringValue.of("create session failure: " + e.toString()))
                    .build())
                    .map(AgentController::convertApiResponse);
        }
    }

    @RequestMapping("/agent/{agentId}/close_session/{sessionId}")
    public Mono<ApiResponse> closeSession(@PathVariable String agentId, @PathVariable String sessionId) {
        checkAgentExists(agentId);
        try {
            return apiActionDelegateService.closeSession(agentId, sessionId)
                    .timeout(Duration.ofMillis(30000))
                    .map(AgentController::convertApiResponse);
        } catch (Throwable e) {
            logger.error("close session failure: " + e.toString(), e);
            return Mono.just(ActionResponse.newBuilder()
                    .setAgentId(agentId)
                    .setSessionId(StringValue.of(sessionId))
                    .setStatus(ResponseStatus.FAILED)
                    .setMessage(StringValue.of("close session failure: " + e.toString()))
                    .build())
                    .map(AgentController::convertApiResponse);
        }
    }

    @RequestMapping("/agent/{agentId}/interrupt_job/{sessionId}")
    public Mono<ApiResponse> interruptJob(@PathVariable String agentId, @PathVariable String sessionId) {
        checkAgentExists(agentId);
        try {
            return apiActionDelegateService.interruptJob(agentId, sessionId)
                    .timeout(Duration.ofMillis(30000))
                    .map(AgentController::convertApiResponse);
        } catch (Throwable e) {
            logger.error("interrupt job failure: " + e.toString(), e);
            return Mono.just(ActionResponse.newBuilder()
                    .setAgentId(agentId)
                    .setSessionId(StringValue.of(sessionId))
                    .setStatus(ResponseStatus.FAILED)
                    .setMessage(StringValue.of("interrupt job failure: " + e.toString()))
                    .build())
                    .map(AgentController::convertApiResponse);
        }
    }

    @PostMapping("/agent/{agentId}/exec")
    public Mono<ApiResponse> execCommand(@PathVariable String agentId, @RequestBody String requestBody) {
        try {
            ApiRequest apiRequest = parseRequest(requestBody);
            long execTimeout = 30000;
            if (apiRequest.getExecTimeout() != null && apiRequest.getExecTimeout() > 0) {
                execTimeout = apiRequest.getExecTimeout();
            }

            ActionRequest request = convertApiRequest(apiRequest);
            return apiActionDelegateService.execCommand(agentId, request)
                    .timeout(Duration.ofMillis(execTimeout))
                    .map(AgentController::convertApiResponse);

        } catch (Throwable e) {
            logger.error("exec command failure: " + e.toString(), e);
            return Mono.just(ActionResponse.newBuilder()
                    .setAgentId(agentId)
                    .setStatus(ResponseStatus.FAILED)
                    .setMessage(StringValue.of("exec command failure: " + e.toString()))
                    .build())
                    .map(AgentController::convertApiResponse);
        }
    }

    @PostMapping("/agent/{agentId}/async_exec")
    public Mono<ApiResponse> asyncExecCommand(@PathVariable String agentId, @RequestBody String requestBody) {
        try {
            ApiRequest apiRequest = parseRequest(requestBody);
            ActionRequest request = convertApiRequest(apiRequest);
            return apiActionDelegateService.asyncExecCommand(agentId, request)
                    .map(AgentController::convertApiResponse);
        } catch (Throwable e) {
            logger.error("async exec command failure: " + e.getMessage(), e);
            return Mono.just(ActionResponse.newBuilder()
                    .setAgentId(agentId)
                    .setStatus(ResponseStatus.FAILED)
                    .setMessage(StringValue.of("async exec command failure: " + e.toString()))
                    .build())
                    .map(AgentController::convertApiResponse);
        }
    }

    @RequestMapping("/agent/{agentId}/results/{requestId}")
    public Mono<ApiResponse> pullResults(@PathVariable String agentId, @PathVariable String requestId,
                                         @RequestParam(value = "timeout", defaultValue = "30000") final int timeout) {
        return apiActionDelegateService.pullResults(agentId, requestId, timeout)
                .map(AgentController::convertApiResponse);
    }

    @GetMapping("/agent/{agentId}/sse_results/{requestId}")
    public SseEmitter sse_results(@PathVariable final String agentId,
                                  @PathVariable final String requestId,
                                  @RequestParam(value = "timeout", defaultValue = "300000") final int timeout) {
        final SseEmitter emitter = new SseEmitter(Long.valueOf(timeout));
        subscribeResults(agentId, requestId, timeout, emitter);
        return emitter;
    }

    @PostMapping("/agent/{agentId}/sse_async_exec")
    public SseEmitter sseAsyncExecCommand(@PathVariable final String agentId,
                                          @RequestBody final String requestBody,
                                          @RequestParam(value = "timeout", defaultValue = "300000") final int timeout) {
        final SseEmitter emitter = new SseEmitter(Long.valueOf(timeout));
        try {
            ApiRequest apiRequest = parseRequest(requestBody);
            ActionRequest request = convertApiRequest(apiRequest);
            ActionResponse actionResponse = apiActionDelegateService.asyncExecCommand(agentId, request).block();
            String requestId = actionResponse.getRequestId();
            subscribeResults(agentId, requestId, timeout, emitter);
        } catch (Throwable e) {
            logger.error("async exec command failure: " + e.getMessage(), e);
            ApiResponse response = new ApiResponse()
                    .setStatus(ApiStatus.FAILED)
                    .setMessage("async exec command failure: " + e.getMessage());
            try {
                emitter.send(JSON.toJSONString(response));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        }
        return emitter;
    }

    private void subscribeResults(String agentId, String requestId, int timeout, final SseEmitter emitter) {
        try {
            apiActionDelegateService.subscribeResults(agentId, requestId, timeout, new ApiActionDelegateService.ResponseListener() {
                @Override
                public boolean onMessage(ActionResponse response) {
                    try {
                        //TODO convert pb message to json
                        ApiResponse apiResponse = convertApiResponse(response);
                        emitter.send(JSON.toJSONString(apiResponse));
                        if (!response.getStatus().equals(ResponseStatus.CONTINUOUS)) {
                            emitter.complete();
                            return false;
                        } else {
                            return true;
                        }
                    } catch (IOException e) {
                        logger.error("send response failure", e);
                        emitter.completeWithError(e);
                        return false;
                    } catch (Throwable e) {
                        logger.error("process response failure", e);
                        emitter.completeWithError(e);
                        return false;
                    }
                }

                @Override
                public boolean onTimeout() {
                    //stop subscribing
                    return false;
                }
            });
            // we could send more events
            //emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    private static ApiResponse convertApiResponse(ActionResponse actionResponse) {
        ApiResponse apiResponse = new ApiResponse()
                .setStatus(getStatus(actionResponse.getStatus()))
                .setAgentId(actionResponse.getAgentId())
                .setRequestId(actionResponse.getRequestId());

        if (actionResponse.hasSessionId()) {
            apiResponse.setSessionId(actionResponse.getSessionId().getValue());
        }
//        if (actionResponse.hasConsumerId()) {
//            apiResponse.setConsumerId(actionResponse.getConsumerId().getValue());
//        }
        if (actionResponse.hasMessage()) {
            apiResponse.setMessage(actionResponse.getMessage().getValue());
        }
        if (actionResponse.hasExecuteResult()) {
            ExecuteResult executeResult = actionResponse.getExecuteResult();
            if (executeResult.hasResultsJson()) {
                apiResponse.setResult(executeResult.getResultsJson().getValue());
            }
        }
        return apiResponse;
    }

    private static ActionRequest convertApiRequest(ApiRequest request) throws Exception {
        RequestAction action = getAction(request.getAction());
        ActionRequest.Builder actionRequestBuilder = ActionRequest.newBuilder()
                .setAction(action);

        if (request.getSessionId() != null) {
            actionRequestBuilder = actionRequestBuilder.setSessionId(StringValue.of(request.getSessionId()));
        }
//        if (request.getConsumerId() != null) {
//            actionRequestBuilder = actionRequestBuilder.setConsumerId(StringValue.of(request.getConsumerId()));
//        }
        if (request.getCommand() != null) {
            int execTimeout = request.getExecTimeout() !=null && request.getExecTimeout() > 0 ? request.getExecTimeout() : 30000;
            actionRequestBuilder = actionRequestBuilder.setExecuteParams(ExecuteParams.newBuilder()
                    .setResultFormat(ResultFormat.JSON)
                    .setCommandLine(request.getCommand())
                    .setExecTimeout(execTimeout)
                    .build());
        }
        return actionRequestBuilder.build();
    }

    private static RequestAction getAction(String action) {
        ApiAction apiAction = ApiAction.valueOf(action.trim().toUpperCase());

        switch (apiAction) {
            case EXEC:
                return RequestAction.EXECUTE;
            case ASYNC_EXEC:
                return RequestAction.ASYNC_EXECUTE;
//            case JOIN_SESSION:
//                return RequestAction.JOIN_SESSION;
            case INIT_SESSION:
                return RequestAction.INIT_SESSION;
            case CLOSE_SESSION:
                return RequestAction.CLOSE_SESSION;
            case INTERRUPT_JOB:
                return RequestAction.INTERRUPT_JOB;
            case OPEN_CONSOLE:
                return RequestAction.OPEN_CONSOLE;
            case CONSOLE_INPUT:
                return RequestAction.CONSOLE_INPUT;
        }
        throw new IllegalArgumentException("Unsupported request action: " + action);
    }

    private static ApiStatus getStatus(ResponseStatus status) {
        switch (status) {
            case SUCCEEDED:
                return ApiStatus.SUCCEEDED;
            case REFUSED:
                return ApiStatus.REFUSED;
            case CONTINUOUS:
                return ApiStatus.CONTINUOUS;
            case INTERRUPTED:
                return ApiStatus.INTERRUPTED;

            case FAILED:
            case UNRECOGNIZED:
                return ApiStatus.FAILED;
        }
        return ApiStatus.FAILED;
    }

    private ApiRequest parseRequest(String requestBody) throws ApiException {
        if (StringUtils.isBlank(requestBody)) {
            throw new ApiException("parse request failed: request body is empty");
        }
        try {
            return JSON.parseObject(requestBody, ApiRequest.class);
        } catch (Exception e) {
            throw new ApiException("parse request failed: " + e.getMessage(), e);
        }
    }

    private AgentVO checkAgentExists(String agentId) {
        Optional<AgentVO> optionalAgentVO = agentManageService.findAgentById(agentId).block();
        if (!optionalAgentVO.isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found");
        }
        return optionalAgentVO.get();
    }

}
