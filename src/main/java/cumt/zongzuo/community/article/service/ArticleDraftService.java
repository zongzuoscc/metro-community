package cumt.zongzuo.community.article.service;

import cumt.zongzuo.community.article.model.ArticleDraft;
import cumt.zongzuo.community.article.web.SaveArticleDraftCommand;

public interface ArticleDraftService {
    ArticleDraft saveDraft(SaveArticleDraftCommand command, long userId);
}
