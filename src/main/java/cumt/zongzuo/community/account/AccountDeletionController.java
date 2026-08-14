package cumt.zongzuo.community.account;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设置页的账号注销、查询与恢复接口。 */
@RestController
@RequestMapping("/api/user/account-deletion")
public class AccountDeletionController {

    private final AccountDeletionService service;

    public AccountDeletionController(AccountDeletionService service) {
        this.service = service;
    }

    @GetMapping
    public Result<AccountDeletionStatus> status() {
        return Result.success(service.status(CurrentUser.id()));
    }

    @PostMapping("/request")
    public Result<AccountDeletionStatus> request(@Valid @RequestBody AccountDeletionRequest request) {
        return Result.success(service.request(CurrentUser.id(), request.confirmation()));
    }

    @PostMapping("/restore")
    public Result<AccountDeletionStatus> restore() {
        return Result.success(service.restore(CurrentUser.id()));
    }
}
