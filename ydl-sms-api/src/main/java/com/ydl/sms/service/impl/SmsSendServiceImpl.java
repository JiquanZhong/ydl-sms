package com.ydl.sms.service.impl;

import com.alibaba.fastjson.JSON;
import com.ydl.sms.dto.SmsBatchParamsDTO;
import com.ydl.sms.dto.SmsParamsDTO;
import com.ydl.sms.dto.SmsSendDTO;
import com.ydl.sms.entity.*;
import com.ydl.sms.enumeration.TemplateType;
import com.ydl.sms.exception.SmsException;
import com.ydl.sms.mapper.ReceiveLogMapper;
import com.ydl.sms.service.*;
import com.ydl.sms.utils.ExceptionUtils;
import com.ydl.sms.utils.SmsEncryptionUtils;
import com.ydl.sms.utils.StringHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 短信发送服务类
 * 1、校验系统是否注册
 * 2、校验秘钥是否通过
 * 3、校验手机号是否在黑名单
 * 4、校验签名
 * 5、校验模板
 * 6、校验参数 模板与参数是否匹配
 * 7、短信分类
 * 8、短信分发
 */
@Service
@Slf4j
public class SmsSendServiceImpl implements SmsSendService {
    private static Pattern PHONE_PATTERN = Pattern.compile("^[1]\\d{10}$");
    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private PlatformService platformService;

    @Autowired
    private BlackListService blackListService;

    @Autowired
    private SignatureService signatureService;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private TimingPushService timingPushService;

    @Autowired
    private ReceiveLogMapper receiveLogMapper;

    /**
     * 1、校验系统是否注册
     *
     * @param accessKeyId
     */
    private PlatformEntity checkAccessKeyId(String accessKeyId) {
        PlatformEntity platformEntity = platformService.getByAccessKeyId(accessKeyId);
        if (null == platformEntity) {
            throw new SmsException("系统未注册");
        }
        if (0 == platformEntity.getIsActive()) {
            throw new SmsException("系统不可用");
        }
        return platformEntity;
    }

    /**
     * 2、校验秘钥是否通过
     *
     * @param timestamp
     * @param accessKeyId
     * @param accessKeySecret
     * @param accessEncryption
     */
    private void checkAuth(String timestamp, String accessKeyId, String accessKeySecret, String accessEncryption) {
        String encryption = SmsEncryptionUtils.encode(timestamp, accessKeyId, accessKeySecret);
        if (accessEncryption.equals(encryption)) {
            return;
        }
        throw new SmsException("鉴权失败");
    }

    /**
     * 3、校验手机号是否在黑名单
     *
     * @param phone
     */
    private void checkBlack(String phone) {
        if (StringUtils.isBlank(phone)) {
            throw new SmsException("手机号为空");
        }
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new SmsException("手机号格式不正确");
        }
        List<String> blackList = blackListService.listByType("1"); // 短信
        if (blackList.contains(phone)) {
            throw new SmsException("黑名单手机号");
        }
    }

    /**
     * 4、校验签名
     * 5、校验模板
     *
     * @param template
     * @param signature
     * @return
     */
    private List<String> checkTemplateAndSignature(String template, String signature) {

        TemplateEntity templateEntity = templateService.getByCode(template);
        SignatureEntity signatureEntity = signatureService.getByCode(signature);

        if (null == templateEntity) {
            throw new SmsException("模板不存在");
        }
        if (null == signatureEntity) {
            throw new SmsException("签名不存在");
        }

        List<ConfigEntity> configs = configService.findByTemplateSignature(templateEntity.getId(), signatureEntity.getId());

        if (CollectionUtils.isEmpty(configs)) {
            throw new SmsException("未找到支持当前签名和模板的通道");
        }

        return configs.stream().map(item -> item.getId()).collect(Collectors.toList());
    }

    /**
     * 6、校验参数 模板与参数是否匹配
     */
    private TemplateEntity checkParams(String template, Map params) {
        TemplateEntity templateEntity = templateService.getByCode(template);
        String content = StringHelper.renderString(templateEntity.getContent(), params);
        if (content.indexOf("${") > 0) {
            throw new SmsException("参数不匹配" + content);
        }

        return templateEntity;
    }

    /**
     * 校验定时发送时间
     *
     * @param sendTime
     */
    private void checkoutSendTime(String sendTime) {
        if (StringUtils.isNotBlank(sendTime)) {
            LocalDateTime localDateTime =
                    LocalDateTime.parse(sendTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            LocalDateTime nowDateTime = LocalDateTime.now().plusMinutes(1L).withSecond(0).minusSeconds(0).withNano(0);
            if (localDateTime.compareTo(nowDateTime) <= 0) {
                throw new SmsException("发送时间过于接近当前时间，无法发送");
            }
        }
    }

    /**
     * 单条短信发送
     *
     * @param smsParamsDTO
     */
    @Override
    public void send(SmsParamsDTO smsParamsDTO) {
        // 校验定时发送时间
        checkoutSendTime(smsParamsDTO.getSendTime());

        // 1 校验系统是否注册
        PlatformEntity platformEntity = checkAccessKeyId(smsParamsDTO.getAccessKeyId());

        if (platformEntity.getNeedAuth() == 1) {
            // 2 校验秘钥是否通过
            checkAuth(smsParamsDTO.getTimestamp(), platformEntity.getAccessKeyId(), platformEntity.getAccessKeySecret(), smsParamsDTO.getEncryption());
        }

        SmsSendDTO smsSendDTO = new SmsSendDTO();
        BeanUtils.copyProperties(smsParamsDTO, smsSendDTO);
        sendSmsMessage(smsSendDTO, platformEntity);
    }

    /**
     * 批量发送短信
     *
     * @param smsBatchParamsDTO
     */
    @Override
    public void batchSend(SmsBatchParamsDTO smsBatchParamsDTO) {

        // 1 校验系统是否注册
        PlatformEntity platformEntity = checkAccessKeyId(smsBatchParamsDTO.getAccessKeyId());

        if (platformEntity.getNeedAuth() == 1) {
            // 2 校验秘钥是否通过
            checkAuth(smsBatchParamsDTO.getTimestamp(), platformEntity.getAccessKeyId(), platformEntity.getAccessKeySecret(), smsBatchParamsDTO.getEncryption());
        }

        Iterator<String> mobileIt = smsBatchParamsDTO.getMobile().iterator();
        Iterator<String> signatureIt = smsBatchParamsDTO.getSignature().iterator();
        Iterator<String> templateIt = smsBatchParamsDTO.getTemplate().iterator();
        Iterator<LinkedHashMap<String, String>> paramsIt = smsBatchParamsDTO.getParams().iterator();

        String mobile = null;
        String signature = null;
        String template = null;
        LinkedHashMap<String, String> param = null;
        StringBuffer errorBf = new StringBuffer();
        if (StringUtils.isBlank(smsBatchParamsDTO.getBatchCode())) {
            String batchCode = UUID.randomUUID().toString();
            smsBatchParamsDTO.setBatchCode(batchCode);
        }
        while (mobileIt.hasNext() || signatureIt.hasNext() || templateIt.hasNext() || paramsIt.hasNext()) {
            if (mobileIt.hasNext()) {
                mobile = mobileIt.next();
            }
            if (signatureIt.hasNext()) {
                signature = signatureIt.next();
            }
            if (templateIt.hasNext()) {
                template = templateIt.next();
            }
            if (paramsIt.hasNext()) {
                param = paramsIt.next();
            }

            SmsSendDTO smsSendDTO = new SmsSendDTO();
            smsSendDTO.setMobile(mobile);
            smsSendDTO.setSignature(signature);
            smsSendDTO.setTemplate(template);
            smsSendDTO.setParams(param);
            smsSendDTO.setSendTime(smsBatchParamsDTO.getSendTime());
            smsSendDTO.setBatchCode(smsBatchParamsDTO.getBatchCode());
            try {
                sendSmsMessage(smsSendDTO, platformEntity);
            } catch (Exception e) {
                String message = e.getMessage();
                errorBf.append(mobile).append(":").append(message).append(";");
            }
        }

        if (errorBf.length() > 0) {
            throw new SmsException(errorBf.toString());
        }
    }

    /**
     * 发送短信 业务校验入口
     */
    private void sendSmsMessage(SmsSendDTO smsSendDTO, PlatformEntity platformEntity) {
        // 3、校验手机号是否在黑名单
        checkBlack(smsSendDTO.getMobile());

        // 4、校验签名
        // 5、校验模板
        List<String> configs = checkTemplateAndSignature(smsSendDTO.getTemplate(), smsSendDTO.getSignature());
        smsSendDTO.setConfigIds(configs);

        // 6、校验参数
        TemplateEntity templateEntity = checkParams(smsSendDTO.getTemplate(), smsSendDTO.getParams());

        // 调用发送接口
        pushSmsMessage(templateEntity, smsSendDTO, platformEntity);

    }


    //缺啥补啥
    /**
     * 根据短信模板分类 并分发
     *
     * @param templateEntity
     * @param smsSendDTO
     * @param platformEntity
     */
    private void pushSmsMessage(TemplateEntity templateEntity, SmsSendDTO smsSendDTO, PlatformEntity platformEntity) {
        // TODO 短信接收服务：将短信信息保存到数据库或者Redis队列
        ReceiveLogEntity receiveLogEntity = new ReceiveLogEntity();
        receiveLogEntity.setApiLogId(UUID.randomUUID().toString());
        long start = System.currentTimeMillis();
        try {
            //1、进行短信分类，分为实时发送短信和定时发送短信
            String sendTime = smsSendDTO.getSendTime();

            String request = JSON.toJSONString(smsSendDTO);
            if (StringUtils.isNotEmpty(sendTime)) {
                //2、如果是定时发送短信则将消息保存到Mysql数据库
                TimingPushEntity timingPushEntity = new TimingPushEntity();
                timingPushEntity.setMobile(smsSendDTO.getMobile());
                timingPushEntity.setTemplate(smsSendDTO.getTemplate());
                timingPushEntity.setRequest(request);
                timingPushEntity.setSignature(smsSendDTO.getSignature());
                timingPushEntity.setTiming(sendTime);

                timingPushService.save(timingPushEntity);
            } else {
                //3、如果是实时发送短信则将消息保存到Redis队列，判断短信模板类型，如果是验证码类型则将消息保存到高优先级队列TOPIC_HIGH_SMS，如果是其他类型则将消息保存到普通队列TOPIC_GENERAL_SMS

                if (templateEntity.getType() == TemplateType.VERIFICATION.getCode()) {
                    //您的验证码是${code}
                    redisTemplate.opsForList().leftPush("TOPIC_HIGH_SMS", request);
                } else {
                    //理财产品${product}
                    redisTemplate.opsForList().leftPush("TOPIC_GENERAL_SMS", request);
                }
            }
            receiveLogEntity.setStatus(1);
        } catch (Exception e) {
            receiveLogEntity.setStatus(0);
            receiveLogEntity.setError(ExceptionUtils.getErrorStackTrace(e));
        } finally {
            //4、保存短信接收日志到Mysql数据库
            receiveLogEntity.setPlatformId(platformEntity.getId());
            receiveLogEntity.setPlatformName(platformEntity.getName());
            receiveLogEntity.setBusiness(smsSendDTO.getBatchCode());
            receiveLogEntity.setConfigIds(StringUtils.join(smsSendDTO.getConfigIds(), ",")); //[1,2,3]--->1,2,3
            receiveLogEntity.setTemplate(smsSendDTO.getTemplate());
            receiveLogEntity.setSignature(smsSendDTO.getSignature());
            receiveLogEntity.setMobile(smsSendDTO.getMobile());
            receiveLogEntity.setRequest(JSON.toJSONString(smsSendDTO.getParams()));
            receiveLogEntity.setUseTime(System.currentTimeMillis() - start);

            receiveLogMapper.insert(receiveLogEntity);
        }
    }
}
