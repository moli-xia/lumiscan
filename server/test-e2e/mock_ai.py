"""Minimal mock of the model APIs the server calls: OpenAI-compatible chat completions and the
DashScope native endpoints (multimodal generation, async image synthesis + task polling)."""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

LAYOUT = {"layouts": [
    {"uniqueId": "a", "type": "title", "alignment": "left", "text": "年度报告\n", "markdownContent": "# 年度报告",
     "pos": [{"x": 300, "y": 50}, {"x": 700, "y": 50}, {"x": 700, "y": 100}, {"x": 300, "y": 100}]},
    {"uniqueId": "b", "type": "text", "text": "正文第一段。\n", "markdownContent": "正文第一段。",
     "pos": [{"x": 100, "y": 150}, {"x": 900, "y": 150}, {"x": 900, "y": 220}, {"x": 100, "y": 220}]},
    {"uniqueId": "c", "type": "table", "numRow": 2, "numCol": 2, "text": "",
     "pos": [{"x": 100, "y": 300}, {"x": 900, "y": 300}, {"x": 900, "y": 500}, {"x": 100, "y": 500}],
     "cells": [
         {"xsc": 0, "xec": 1, "ysc": 0, "yec": 0, "layouts": [{"text": "合并表头\n"}]},
         {"xsc": 0, "xec": 0, "ysc": 1, "yec": 1, "layouts": [{"text": "A\n"}]},
         {"xsc": 1, "xec": 1, "ysc": 1, "yec": 1, "layouts": [{"text": "B\n"}]}]},
    {"uniqueId": "a", "type": "title", "text": "年度报告\n", "pos": []},
]}

# Models the mock "Bailian" still offers; anything with "edit-plus" in the name has been retired.
MODELS = ["qwen-plus", "qwen3-vl-plus", "qwen3-vl-flash", "qwen3.5-ocr", "qwen-vl-ocr-latest",
          "qwen-image-3.0", "qwen-image-3.0-pro", "wanx2.1-imageedit"] + [f"filler-{i}" for i in range(150)]
THROTTLE_ONCE = [True]  # the first image edit is rate limited once (HTTP 429)

class H(BaseHTTPRequestHandler):
    def _send(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers()
        self.wfile.write(data)

    def _auth(self):
        if self.headers.get("Authorization") != "Bearer test-key":
            self._send({"error": {"message": "Invalid API key"}, "message": "Invalid API key"}, 401)
            return False
        return True

    def do_GET(self):
        if not self._auth(): return
        if "/models?" in self.path:
            q = dict(p.split("=") for p in self.path.split("?", 1)[1].split("&"))
            page, size = int(q.get("page_no", 1)), int(q.get("page_size", 10))
            self._send({"output": {"total": len(MODELS), "page_no": page, "page_size": size,
                                   "models": [{"model": m} for m in MODELS[(page - 1) * size:page * size]]}})
        elif self.path.endswith("/tasks/t1"):
            self._send({"output": {"task_id": "t1", "task_status": "SUCCEEDED", "results": [{"url": "http://127.0.0.1:18999/sr.png"}]}})
        else:
            self._send({"message": "not found"}, 404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        if not self._auth(): return
        if "edit-plus" in body.get("model", ""):
            if self.path.endswith("/chat/completions"):
                self._send({"error": {"code": "model_not_found", "message": f"The model `{body['model']}` does not exist or you do not have access to it."}}, 404)
            else:
                self._send({"code": "InvalidParameter", "message": "Model not exist."}, 404)
            return
        if self.path.endswith("/chat/completions"):
            msgs = body.get("messages", [])
            last = msgs[-1]["content"]
            prompt = " ".join(p.get("text", "") for p in last if isinstance(p, dict)) if isinstance(last, list) else last
            has_image = isinstance(last, list) or any(isinstance(m["content"], list) for m in msgs)
            if "清点数量" in prompt:
                if "原生格式" in prompt:
                    reply = '[{"point_2d": [100, 200], "label": "瓶子"}, {"point_2d": [300, 400], "label": "瓶子"}, {"bbox_2d": [500, 500, 600, 700], "label": "瓶子"}]'
                elif "截断" in prompt:
                    reply = '```json\n{"label": "钢筋", "points": [[10, 20], [30, 40], [50, 6'
                else:
                    name = "钢管" if "要清点的物体是：钢管" in prompt else "药片"
                    reply = '```json\n{"label": "%s", "points": [[100, 200], [300, 400], [300, 401], [1200, -5]]}\n```' % name
                    if '"' in prompt.split("要清点的物体是：")[-1].split("。")[0]: reply = "hint not sanitised"
            elif "bbox_2d" in prompt:
                reply = '```json\n[{"bbox_2d": [100, 600, 500, 650], "label": "签名"}, {"bbox_2d": [900, 900, 950, 950], "label": "勾"}]\n```'
            elif "text content from the image" in prompt:
                reply = "# 标题\n\n第一段文字\n\n| 模块 | 进度 |\n| --- | --- |\n| 扫描 | 100% |"
            else:
                sys_prompt = msgs[0]["content"] if msgs and msgs[0]["role"] == "system" else ""
                reply = f"[mock {body['model']}] system={sys_prompt[:12]} image={has_image} msgs={len(msgs)} extra={body.get('enable_thinking')}"
            self._send({"choices": [{"message": {"role": "assistant", "content": reply}}], "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150}})
        elif self.path.endswith("/multimodal-generation/generation"):
            params = body.get("parameters", {})
            if params.get("ocr_options", {}).get("task") == "document_parsing":
                self._send({"output": {"choices": [{"message": {"content": [{"ocr_result": LAYOUT, "text": "..."}]}}]}, "usage": {"input_tokens": 2000, "output_tokens": 300}})
            else:
                if THROTTLE_ONCE[0]:
                    THROTTLE_ONCE[0] = False
                    self._send({"code": "Throttling.RateQuota", "message": "Requests rate limit exceeded, please try again later."}, 429); return
                size = params.get("size", "")
                self._send({"output": {"choices": [{"message": {"content": [{"image": f"http://127.0.0.1:18999/edit.png?model={body['model']}&size={size}"}]}}]}, "usage": {"image_count": 1}})
        elif self.path.endswith("/image2image/image-synthesis"):
            if self.headers.get("X-DashScope-Async") != "enable":
                self._send({"message": "current user api does not support synchronous calls"}, 400); return
            self._send({"output": {"task_id": "t1", "task_status": "PENDING"}})
        else:
            self._send({"message": "not found"}, 404)

    def log_message(self, *a): pass

HTTPServer(("127.0.0.1", 18999), H).serve_forever()
