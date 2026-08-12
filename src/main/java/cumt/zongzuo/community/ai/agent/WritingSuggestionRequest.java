package cumt.zongzuo.community.ai.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 写作建议请求携带选区与本地版本，后端不获得任何直接写草稿的能力。 */
public record WritingSuggestionRequest(
        @NotBlank String operation,
        @Size(max = 100) String title,
        @NotNull @Size(max = 20_000) String content,
        @NotBlank @Size(max = 20_000) String selectedText,
        @Min(0) int selectionFrom,
        @Min(0) int selectionTo,
        @Min(0) @Max(Integer.MAX_VALUE) long documentVersion) {
}
