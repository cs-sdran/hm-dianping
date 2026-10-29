package com.hmdp;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.BlogController;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.service.IBlogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BlogFeedControllerTest {
    private IBlogService blogs;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        blogs = mock(IBlogService.class);
        BlogController controller = new BlogController();
        ReflectionTestUtils.setField(controller, "blogService", blogs);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WebExceptionAdvice()).build();
    }

    private Result emptyPage(long max, int offset) {
        ScrollResult page = new ScrollResult();
        page.setList(Collections.emptyList());
        page.setMinTime(max);
        page.setOffset(offset);
        return Result.ok(page);
    }

    @Test
    void bindsCursorAndOffsetAndReturnsScrollShape() throws Exception {
        when(blogs.queryBlogOfFollow(1000, 3)).thenReturn(emptyPage(1000, 3));
        mvc.perform(get("/blog/of/follow").param("lastId", "1000").param("offset", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.minTime").value(1000))
                .andExpect(jsonPath("$.data.offset").value(3));
        verify(blogs).queryBlogOfFollow(1000, 3);
    }

    @Test
    void defaultsOffsetToZero() throws Exception {
        when(blogs.queryBlogOfFollow(1000, 0)).thenReturn(emptyPage(1000, 0));
        mvc.perform(get("/blog/of/follow").param("lastId", "1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(blogs).queryBlogOfFollow(1000, 0);
    }

    @Test
    void missingAndMalformedParametersAreClientErrors() throws Exception {
        mvc.perform(get("/blog/of/follow")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/blog/of/follow").param("lastId", "bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/blog/of/follow").param("lastId", "1000").param("offset", "bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(blogs);
    }
}
