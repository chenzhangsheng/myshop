package cn.lili.modules.wallet.entity.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotEmpty;

@Data
@ApiModel(value = "钱包登录参数")
@NoArgsConstructor
@AllArgsConstructor
public class WalletLoginDTO {

    @NotEmpty(message = "sign not empty")
    @ApiModelProperty(value = "钱包登录签名")
    private String sign;

    @NotEmpty(message = "sessionId not empty")
    @ApiModelProperty(value = "钱包登录会话Id")
    private String sessionId;

    @NotEmpty(message = "address not empty")
    @ApiModelProperty(value = "钱包地址")
    private String address;
}
