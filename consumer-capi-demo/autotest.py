#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KMP CAPI 自动化测试脚本 - 逐模块UI版本
功能：
1. 逐个进入模块页面
2. 点击"运行验证"
3. 从页面UI读取清单统计
4. 生成HTML+CSV测试报告
"""

import csv
import subprocess
import sys
import os
import re
import json
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Tuple, Optional
import time

# 项目根目录
PROJECT_ROOT = Path(__file__).parent
CSV_FILE = PROJECT_ROOT / "testFiles" / "用例.csv"
REPORT_DIR = PROJECT_ROOT / "test_reports"

# 模块UI标签映射（匹配图片中的实际文本）
MODULE_UI_LABELS = {
    "RDB": ["模块1", "RDB模块"],
    "CommonEvent": ["模块2", "CommonEvent", "公共事件模块"],
    "HuksKeyApi": ["模块3", "HuksKeyApi", "密钥管理模块"],
    "NetConnection": ["模块4", "NetConnection", "网络连接模块"],
    "HiAppEvent": ["模块5", "HiAppEvent", "用户打点模块"],
    "HiLog": ["模块6", "HiLog", "日志模块"],
    "Drawing": ["模块7", "Drawing模块"],
    "Hms": ["模块8", "HMS模块", "HMS扩展Kit模块"],
    "Failure": ["失败场景模块", "失败场景"],
    "VersionGuard": ["版本检测机制模块", "版本检测"],
}

# 模块等待时间（秒）
MODULE_WAIT_TIME = {
    "RDB": 25,
    "CommonEvent": 18,
    "HuksKeyApi": 12,
    "NetConnection": 10,
    "HiAppEvent": 12,
    "HiLog": 6,
    "Drawing": 35,
    "Hms": 10,
    "Failure": 12,
    "VersionGuard": 18,
}


class TestCase:
    """测试用例"""
    def __init__(self, row: List[str]):
        self.name = row[3].strip()
        self.case_id = row[4].strip()
        self.level = row[5].strip()
        self.expected_result = row[16].strip()
        self.module = self._extract_module()
        self.function_name = self._extract_function_name()
        
    def _extract_module(self) -> str:
        """提取模块名"""
        n = self.name
        # 优先：失败场景模块
        if any(k in n for k in ('失败场景模块', '失败场景', 'Failure')):
            return "Failure"
        # 特殊处理：版本检测相关函数优先识别
        if any(k in n for k in ('OH_Huks_WrapKey', 'OH_HiDebug_', 'OH_HiTrace_', 'OH_AudioStreamBuilder_', 'OH_Pasteboard_')):
            return "VersionGuard"
        # 通用规则
        if any(k in n for k in ('版本检测', 'VersionGuard', '弱符号校验', 'OH_PLAYER_', 'OH_AVCODEC_')):
            return "VersionGuard"
        if any(k in n for k in ('RDB模块', 'OH_Rdb_', 'OH_VBucket_', 'OH_RdbTrans', 'OH_Crypto_')):
            return "RDB"
        if any(k in n for k in ('CommonEvent', 'OH_CommonEvent_', 'COMMON_EVENT_')):
            return "CommonEvent"
        if any(k in n for k in ('HuksKeyApi', 'OH_Huks_')):
            return "HuksKeyApi"
        if any(k in n for k in ('NetConnection', 'OH_NetConn_')):
            return "NetConnection"
        if any(k in n for k in ('HiAppEvent', 'OH_HiAppEvent_', 'DOMAIN_OS', 'EVENT_APP_CRASH', 'PARAM_USER_ID')):
            return "HiAppEvent"
        if any(k in n for k in ('HiLog', 'OH_LOG_', 'LOG_DOMAIN', 'LOG_TAG')):
            return "HiLog"
        if any(k in n for k in ('Drawing', 'OH_Drawing_')):
            return "Drawing"
        if any(k in n for k in ('HMS', 'HMS_Rcp_', 'HMS_HiAI_', 'RemoteCommunication', 'CANN')):
            return "Hms"
        return "Unknown"

    def _extract_function_name(self) -> str:
        """提取函数名"""
        match = re.search(
            r'验证(?:函数|常量|宏|API\d+新增(?:函数|常量))([A-Za-z_][A-Za-z0-9_]*)',
            self.name
        )
        return match.group(1) if match else ""


class ModuleTestRunner:
    """逐模块测试运行器"""
    
    def __init__(self):
        self.device = os.environ["HDC_DEVICE"]
        self.test_cases: List[TestCase] = []
        self.test_results: Dict[str, Dict] = {}
        self.ui_results: Dict[str, Dict[str, str]] = {}  # {module: {func: result}}
        self.debug_log = []  # 统一的调试日志
        
    def _hdc(self, args: List[str], timeout: int = 30) -> subprocess.CompletedProcess:
        """执行hdc命令"""
        try:
            return subprocess.run(
                ["hdc", "-t", self.device] + args,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout
            )
        except subprocess.TimeoutExpired:
            print(f"⚠️  命令超时: hdc {' '.join(args)}")
            return subprocess.CompletedProcess(args, 1, "", "Timeout")
        except Exception as e:
            print(f"❌ 命令失败: {e}")
            return subprocess.CompletedProcess(args, 1, "", str(e))

    def _dump_layout(self) -> str:
        """dump 当前页面布局并返回文本。用 -i 不合并窗口——App 窗口常被 sceneboard
        遮挡层级，合并模式抓不到 com.kotlin.demo 节点；-i 输出所有窗口根节点（list）。
        下游 find_button/extract_page_text 期望单个根 dict，这里把 list 合成虚拟根。"""
        layout_file = "/data/local/tmp/_autotest_layout.json"
        self._hdc(["shell", "uitest", "dumpLayout", "-i", "-p", layout_file], timeout=15)
        raw = self._hdc(["shell", "cat", layout_file], timeout=15).stdout
        try:
            data = json.loads(raw)
            if isinstance(data, list):
                # 多窗口 list → 合成虚拟根，children 为各窗口根节点
                return json.dumps({"attributes": {}, "children": data})
            return raw  # 已经是单个 dict
        except Exception:
            return raw

    def wake_unlock(self) -> None:
        """亮屏 + 上滑解锁。设备锁屏时 aa start 虽返回 success 但 App UI 不渲染，
        dump 全是 com.ohos.sceneboard/ScreenLock。每个模块开始前调用。
        keyEvent 224 = WAKEUP（比 Power 可靠，Power 会切亮/灭）。"""
        self._hdc(["shell", "uitest", "uiInput", "keyEvent", "224"], timeout=8)
        time.sleep(0.4)
        self._hdc(["shell", "uitest", "uiInput", "swipe", "500", "2000", "500", "300", "400"], timeout=8)
        time.sleep(0.4)

    def _poll_text(self, needles, timeout: int = 10, interval: float = 0.5) -> Optional[str]:
        """轮询 dumpLayout 直到页面出现任一目标文字（短 sleep + 断言），超时返回 None。
        dump 过短（<200 字符，黑屏/锁屏）时触发亮屏解锁重试。"""
        if isinstance(needles, str):
            needles = [needles]
        deadline = time.time() + timeout
        black_streak = 0
        while time.time() < deadline:
            last = self._dump_layout()
            if len(last) < 200:
                black_streak += 1
                if black_streak >= 3:
                    self.wake_unlock()
                    black_streak = 0
            else:
                black_streak = 0
            if any(n in last for n in needles):
                return last
            time.sleep(interval)
        return None

    def _poll_button(self, patterns, timeout: int = 10, interval: float = 0.5) -> Optional[Tuple[int, int]]:
        """轮询 dumpLayout 直到目标按钮出现，返回坐标，超时返回 None。
        dump 过短（黑屏）时触发亮屏解锁重试。"""
        deadline = time.time() + timeout
        black_streak = 0
        while time.time() < deadline:
            layout = self._dump_layout()
            if len(layout) < 200:
                black_streak += 1
                if black_streak >= 3:
                    self.wake_unlock()
                    black_streak = 0
            else:
                black_streak = 0
            pos = self.find_button(layout, patterns)
            if pos:
                return pos
            time.sleep(interval)
        return None
    
    def parse_csv(self):
        """解析测试用例CSV"""
        print(f"📖 解析测试用例: {CSV_FILE}")
        
        with open(CSV_FILE, 'r', encoding='utf-8-sig') as f:
            reader = csv.reader(f)
            next(reader)  # 跳过表头
            
            for row in reader:
                if len(row) < 17:
                    continue
                if row[4].startswith("KMP_"):
                    tc = TestCase(row)
                    if tc.module != "Unknown":
                        self.test_cases.append(tc)
        
        print(f"✅ 解析完成，共 {len(self.test_cases)} 个用例")
        
        # 统计各模块用例数
        module_counts = {}
        for tc in self.test_cases:
            module_counts[tc.module] = module_counts.get(tc.module, 0) + 1
        
        print("\n📊 用例分布:")
        for module, count in sorted(module_counts.items()):
            print(f"  - {module}: {count} 个")
    
    def find_button(self, layout_json: str, text_patterns) -> Optional[Tuple[int, int]]:
        """从UI布局找按钮坐标（支持多个文本模式）"""
        if isinstance(text_patterns, str):
            text_patterns = [text_patterns]
        
        try:
            data = json.loads(layout_json)
            
            def walk(node):
                attrs = node.get('attributes', {})
                node_text = attrs.get('text', '')
                node_type = attrs.get('type', '')
                bounds = attrs.get('bounds', '')
                clickable = attrs.get('clickable', 'false')
                
                # 检查是否匹配任一文本模式
                text_match = any(pattern in node_text for pattern in text_patterns)
                
                # 可点击的元素（Button、ListItem、Text等）
                is_clickable = (
                    'Button' in node_type or 
                    'ListItem' in node_type or
                    'Text' in node_type or
                    clickable.lower() == 'true'
                )
                
                if text_match and is_clickable and bounds:
                    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
                    if m:
                        x1, y1, x2, y2 = map(int, m.groups())
                        center = ((x1 + x2) // 2, (y1 + y2) // 2)
                        print(f"    找到匹配: \"{node_text}\" at {center}")
                        return center
                
                for child in node.get('children', []):
                    result = walk(child)
                    if result:
                        return result
                return None
            
            return walk(data)
        except Exception as e:
            print(f"⚠️  解析布局失败: {e}")
            return None
    
    def extract_page_text(self, layout_json: str) -> str:
        """提取页面所有文本"""
        texts = []
        
        try:
            data = json.loads(layout_json)
            
            def walk(node):
                attrs = node.get('attributes', {})
                text = attrs.get('text', '').strip()
                if text:
                    texts.append(text)
                for child in node.get('children', []):
                    walk(child)
            
            walk(data)
            return '\n'.join(texts)
        except Exception as e:
            print(f"⚠️  提取文本失败: {e}")
            return ""
    
    def parse_manifest(self, page_text: str, module_name: str = "") -> Dict[str, Dict[str, str]]:
        """
        解析页面清单统计
        返回: {function_name: {'result': 'xx', 'reason': 'xxx'}}
        """
        results = {}
        lines = page_text.split('\n')
        in_manifest = False
        fail_reasons = {}  # 函数 -> 失败原因提取
        
        print("\n🔍 解析清单统计...")
        
        for i, line in enumerate(lines):
            line = line.strip()
            
            # 检测清单统计开始
            if '清单统计' in line:
                in_manifest = True
                print(f"  找到清单统计: {line}")
                continue
            
            # 检测清单统计结束
            if in_manifest and ('清单汇总' in line or '判定失败' in line or '宏定义列表' in line):
                print(f"  清单统计结束: {line}")
                break
            
            # 解析清单条目
            if in_manifest:
                # 格式: "1. OH_Func_Name — 成功"
                m = re.match(r'^\d+\.\s+([A-Z][A-Za-z0-9_]+)\s*[—-]\s*(.+)$', line)
                if m:
                    func_name = m.group(1)
                    status = m.group(2).strip()
                    
                    # 跳过宏比对行
                    if '固定参照' in status:
                        continue
                    
                    # 映射状态
                    if '成功' in status:
                        result = '成功'
                    elif 'API版本不符' in status:
                        result = 'API版本不符'
                    elif '失败' in status:
                        result = '失败'
                    elif '未执行' in status:
                        result = '未执行'
                    else:
                        result = '未知'

                    # Failure 模块：验证失败场景，函数返回预期错误码时 UI 标"失败"，
                    # 实为测试通过（预期错误码返回），反转为"成功"
                    if module_name == 'Failure' and result == '失败':
                        result = '成功'
                        print(f"    {func_name}: Failure 模块预期错误码返回 → 记成功")

                    results[func_name] = {'result': result, 'reason': ''}
                    print(f"    {func_name}: {result}")
        
        print(f"✅ 解析到 {len(results)} 个函数结果")
        
        # 解析「判定失败明细」填充原因
        print("\n🔍 解析判定失败明细...")
        in_fail_detail = False
        for i, line in enumerate(lines):
            t = line.strip()
            if t.startswith('---------- 判定失败明细'):
                in_fail_detail = True
                continue
            if in_fail_detail and (t.startswith('----------') or 'API 版本不符' in t or '未执行明细' in t):
                in_fail_detail = False
                continue
            if in_fail_detail and t.startswith('•'):
                # 尝试提取函数名与简要原因
                m = re.search(r'(OH_[A-Za-z0-9_]+)', t)
                if m:
                    fn = m.group(1)
                    # 去掉项目符号与函数名，保留中文说明
                    reason = t
                    # 若存在中文冒号/顿号，尽量保留“释义”起始后的内容
                    parts = re.split(r'[：:]', t, maxsplit=1)
                    if len(parts) == 2 and parts[1].strip():
                        reason = parts[1].strip()
                    fail_reasons[fn] = reason
                    print(f"    原因映射: {fn} -> {reason[:60] + ('...' if len(reason)>60 else '')}")
        
        # 回填原因
        for fn in results.keys():
            if not results[fn].get('reason') and fn in fail_reasons:
                results[fn]['reason'] = fail_reasons[fn]
        
        # 解析宏定义列表
        print("\n🔍 解析宏定义列表...")
        in_macro_list = False
        macro_count = 0
        
        for i, line in enumerate(lines):
            line = line.strip()
            
            # 检测宏定义列表开始
            if '宏定义列表' in line:
                in_macro_list = True
                print(f"  找到宏定义列表: {line}")
                continue
            
            # 检测宏定义列表结束
            if in_macro_list and ('宏列表汇总' in line or '判定失败' in line or line.startswith('---')):
                print(f"  宏定义列表结束: {line}")
                break
            
            # 解析宏定义条目
            if in_macro_list:
                # 格式: "1. MACRO_NAME — 固定参照 "value" — cinterop "value" — 一致"
                # 或: "1. MACRO_NAME — 固定参照 "value" — cinterop "value" — 不一致"
                m = re.match(r'^\d+\.\s+([A-Z][A-Z0-9_]+)\s+[—-].*[—-]\s*(一致|不一致)\s*$', line)
                if m:
                    macro_name = m.group(1)
                    status = m.group(2).strip()
                    
                    # 映射状态
                    if status == '一致':
                        result = '成功'
                    elif status == '不一致':
                        result = '失败'
                    else:
                        result = '未知'
                    
                    results[macro_name] = {'result': result, 'reason': ''}
                    macro_count += 1
                    print(f"    {macro_name}: {result}")
        
        print(f"✅ 解析到 {macro_count} 个宏定义结果")
        
        # 解析常量列表（API 版本引入级别列表中的常量部分）
        print("\n🔍 解析常量列表...")
        in_const_list = False
        const_count = 0
        
        for i, line in enumerate(lines):
            line = line.strip()
            
            # 检测常量列表开始（在"API 版本引入级别列表"之后的"常量列表"）
            if '常量列表' in line:
                in_const_list = True
                print(f"  找到常量列表: {line}")
                continue
            
            # 检测常量列表结束
            if in_const_list and (line.startswith('---') or 'API 版本不符' in line or '判定失败' in line):
                print(f"  常量列表结束: {line}")
                break
            
            # 解析常量条目
            if in_const_list:
                # 格式1: "1. OH_PLAYER_PLAYBACK_RATE — API20 — 成功" (VersionGuard等模块)
                m = re.match(r'^\d+\.\s+([A-Z][A-Z0-9_]+)\s+[—-]\s+API\d+\s+[—-]\s+(.+)$', line)
                if m:
                    const_name = m.group(1)
                    status = m.group(2).strip()
                    
                    # 映射状态
                    if '成功' in status:
                        result = '成功'
                    elif '触发弱版本校验' in status or 'API版本不符' in status:
                        result = 'API版本不符'
                    elif '失败' in status:
                        result = '失败'
                    elif '未执行' in status:
                        result = '未执行'
                    else:
                        result = '未知'
                    
                    results[const_name] = {'result': result, 'reason': ''}
                    const_count += 1
                    print(f"    {const_name}: {result}")
                else:
                    # 格式2: "1. COMMON_EVENT_BATTERY_CHANGED — 固定参照 xxx — cinterop=xxx — 一致" (CommonEvent模块)
                    m2 = re.match(r'^\d+\.\s+([A-Z][A-Z0-9_]+)\s+[—-].+[—-]\s+(一致|不一致)$', line)
                    if m2:
                        const_name = m2.group(1)
                        status = m2.group(2).strip()
                        
                        # 映射状态：一致=成功，不一致=失败
                        result = '成功' if status == '一致' else '失败'
                        
                        results[const_name] = {'result': result, 'reason': ''}
                        const_count += 1
                        print(f"    {const_name}: {result} (CommonEvent格式)")
        
        print(f"✅ 解析到 {const_count} 个常量结果")
        
        # 解析未执行明细（提取未执行原因）
        print("\n🔍 解析未执行明细...")
        in_not_run = False
        not_run_count = 0
        current_func = None
        
        for i, line in enumerate(lines):
            line_stripped = line.strip()
            
            # 检测未执行明细开始
            if '未执行明细' in line_stripped:
                in_not_run = True
                print(f"  找到未执行明细: {line_stripped}")
                continue
            
            # 检测未执行明细结束（到达文件末尾或下一个分隔线）
            if in_not_run and i == len(lines) - 1:
                break
            
            # 解析未执行条目
            if in_not_run:
                # 跳过释义和空行
                if '释义' in line_stripped or line_stripped == '（无）' or not line_stripped:
                    continue
                
                # 格式: "• OH_Func_Name"
                if line_stripped.startswith('•'):
                    func_name = line_stripped[1:].strip()
                    current_func = func_name
                    # 如果这个函数之前被标记为未执行，准备更新其原因
                    if current_func in results and results[current_func]['result'] == '未执行':
                        not_run_count += 1
                        print(f"    找到未执行函数: {current_func}")
                # 格式: "  → 未执行：原因说明"
                elif line_stripped.startswith('→') and current_func:
                    reason_text = line_stripped[1:].strip()
                    # 提取"未执行："后面的内容
                    if '未执行：' in reason_text:
                        reason = reason_text.split('未执行：', 1)[1].strip()
                    elif '未执行' in reason_text:
                        reason = reason_text.replace('未执行', '').strip().lstrip('：:').strip()
                    else:
                        reason = reason_text
                    
                    # 更新结果中的原因
                    if current_func in results:
                        results[current_func]['reason'] = reason
                        print(f"      原因: {reason}")
                    current_func = None
        
        print(f"✅ 解析到 {not_run_count} 个未执行函数的原因")
        print(f"📊 总计解析: {len(results)} 项（函数 + 宏定义 + 常量）")
        return results
    
    def run_module(self, module_name: str) -> bool:
        """运行单个模块测试"""
        print(f"\n{'='*70}")
        print(f"📦 模块: {module_name}")
        print(f"{'='*70}")
        
        BUNDLE = "com.kotlin.demo"
        ABILITY = "EntryAbility"
        layout_file = "/data/local/tmp/_autotest_layout.json"
        
        # 1. 启动App回到主页（轮询等主页渲染，不固定 sleep）
        print("🚀 启动App...")
        self.wake_unlock()
        self._hdc(["shell", "aa", "start", "-a", ABILITY, "-b", BUNDLE], timeout=15)
        # 2. 轮询查找模块按钮（短 sleep + 断言按钮出现）
        module_patterns = MODULE_UI_LABELS.get(module_name, [module_name])
        print(f"🔍 查找模块按钮: {module_patterns}")
        home_layout = self._poll_button(module_patterns, timeout=10)
        if not home_layout:
            print(f"❌ 未找到模块按钮")
            self.debug_log.append(f"\n模块: {module_name} - 主页未找到按钮\n{self._dump_layout()[:2000]}")
            return False
        btn_pos = home_layout
        # 记录主页布局用于调试
        self.debug_log.append(f"\n{'='*70}\n模块: {module_name} - 主页布局\n{'='*70}\n{self._dump_layout()[:2000]}")
        print(f"✅ 按钮位置: {btn_pos}")

        # 3. 点击模块按钮
        print(f"👆 点击模块...")
        self._hdc(["shell", "uitest", "uiInput", "click", str(btn_pos[0]), str(btn_pos[1])], timeout=10)

        # 4. 轮询查找"运行验证"按钮（断言页面切换后按钮出现）
        print(f"🔍 查找运行验证按钮...")
        run_btn_pos = self._poll_button(["运行验证", "运行"], timeout=10)
        if not run_btn_pos:
            print(f"❌ 未找到运行验证按钮")
            self.debug_log.append(f"\n模块: {module_name} - 模块页面未找到运行按钮\n{self._dump_layout()[:2000]}")
            return False
        self.debug_log.append(f"\n模块: {module_name} - 模块页面布局\n{self._dump_layout()[:2000]}")
        print(f"✅ 按钮位置: {run_btn_pos}")

        # 5. 点击运行验证
        print(f"👆 点击运行验证...")
        self._hdc(["shell", "uitest", "uiInput", "click", str(run_btn_pos[0]), str(run_btn_pos[1])], timeout=10)

        # 6. 轮询等待测试完成：断言"清单统计/清单汇总"出现，上限 MODULE_WAIT_TIME
        wait_max = MODULE_WAIT_TIME.get(module_name, 15)
        print(f"⏳ 等待测试完成 (最多 {wait_max}秒，结果出现即继续)...")
        layout = self._poll_text(["清单统计", "清单汇总"], timeout=wait_max, interval=0.5)
        if layout is None:
            print(f"⚠️  {module_name} 等待 {wait_max}秒 未检测到清单统计")
            layout = self._dump_layout()

        # 7. 读取页面内容
        print(f"📱 读取页面结果...")
        page_text = self.extract_page_text(layout)
        self.debug_log.append(f"\n模块: {module_name} - 测试结果页面\n{page_text}")
        
        # 9. 解析清单统计
        results = self.parse_manifest(page_text, module_name)
        
        if results:
            self.ui_results[module_name] = results
            print(f"✅ {module_name} 完成，获得 {len(results)} 个结果")
        else:
            print(f"⚠️  {module_name} 未找到清单统计")
        
        # 10. 返回主页（轮询找返回按钮 + 点击后断言主页出现，不固定 sleep）
        print(f"🔙 返回主页...")
        back_btn_pos = self._poll_button(["返回"], timeout=5)
        if back_btn_pos:
            print(f"✅ 找到返回按钮，坐标: {back_btn_pos}")
            self._hdc(["shell", "uitest", "uiInput", "click", str(back_btn_pos[0]), str(back_btn_pos[1])], timeout=5)
            # 轮询等主页标志出现（任意已知模块标签）
            if self._poll_text(["RDB", "CommonEvent", "HiLog", "HuksKeyApi"], timeout=5, interval=0.3):
                print(f"✅ 已返回主页")
            else:
                print(f"⚠️  返回后未确认主页，继续下个模块")
        else:
            print(f"⚠️ 未找到返回按钮，使用物理返回键")
            self._hdc(["shell", "uitest", "uiInput", "keyEvent", "Back"], timeout=5)
            self._poll_text(["RDB", "CommonEvent", "HiLog", "HuksKeyApi"], timeout=3, interval=0.3)
        
        return len(results) > 0 if results else False
    
    def run_all_modules(self):
        """运行所有模块"""
        print("\n" + "="*70)
        print("🤖 开始逐模块自动测试")
        print("="*70)
        
        # 检查设备
        r = self._hdc(["list", "targets"])
        if not r.stdout.strip():
            print("❌ 未检测到设备")
            return False
        print(f"✅ 设备已连接: {r.stdout.strip()}")

        # 开头：退出应用 + 重新点亮屏幕，确保从干净状态开始
        print("💡 亮屏 + 退出旧应用进程...")
        self.wake_unlock()
        self._hdc(["shell", "aa", "force-stop", "com.kotlin.demo"], timeout=10)
        time.sleep(1)
        
        modules = ["RDB", "CommonEvent", "HuksKeyApi", "NetConnection",
                   "HiAppEvent", "HiLog", "Drawing", "Hms", "Failure", "VersionGuard"]
        
        success_count = 0
        for idx, module in enumerate(modules, 1):
            print(f"\n进度: [{idx}/{len(modules)}]")
            t0 = time.time()
            if self.run_module(module):
                success_count += 1
            print(f"⏱️  {module} 耗时 {time.time() - t0:.1f}s")
            # 模块间不再固定 sleep：run_module 结尾已轮询确认返回主页
        
        print(f"\n{'='*70}")
        print(f"✅ 完成！成功: {success_count}/{len(modules)} 个模块")
        print(f"{'='*70}")
        
        # 保存统一的调试日志
        if self.debug_log:
            debug_file = REPORT_DIR / "debug_all_modules.txt"
            debug_file.parent.mkdir(parents=True, exist_ok=True)
            with open(debug_file, 'w', encoding='utf-8') as f:
                f.write('\n'.join(self.debug_log))
            print(f"\n💾 调试日志已保存: {debug_file}")
        
        return success_count > 0
    
    def collect_results(self):
        """匹配UI结果到测试用例"""
        print("\n📋 匹配测试结果...")
        
        for tc in self.test_cases:
            module_results = self.ui_results.get(tc.module, {})
            
            if tc.function_name in module_results:
                func_result = module_results[tc.function_name]
                self.test_results[tc.case_id] = {
                    'result': func_result['result'],
                    'reason': func_result['reason'],
                    'module': tc.module,
                    'function': tc.function_name
                }
            else:
                self.test_results[tc.case_id] = {
                    'result': '未找到',
                    'reason': f'函数未在{tc.module}页面清单中找到',
                    'module': tc.module,
                    'function': tc.function_name
                }
        
        # 统计
        stats = {}
        for r in self.test_results.values():
            result = r['result']
            stats[result] = stats.get(result, 0) + 1
        
        print(f"\n📊 测试结果统计:")
        print(f"  ✅ 成功: {stats.get('成功', 0)}")
        print(f"  ❌ 失败: {stats.get('失败', 0)}")
        print(f"  ⏭️  未执行: {stats.get('未执行', 0)}")
        print(f"  ⚠️  API版本不符: {stats.get('API版本不符', 0)}")
        print(f"  ❓ 未找到: {stats.get('未找到', 0)}")
        print(f"  📝 总计: {len(self.test_results)}")
    
    def generate_reports(self):
        """生成HTML和CSV报告"""
        print("\n📄 生成测试报告...")
        
        REPORT_DIR.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        
        # CSV报告
        csv_file = REPORT_DIR / "test_report.csv"
        with open(csv_file, 'w', encoding='utf-8', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(['用例编号', '用例名称', '模块', '函数/常量名', '测试结果', '失败原因', '测试时间'])
            
            for tc in self.test_cases:
                r = self.test_results.get(tc.case_id, {'result': '未运行', 'reason': ''})
                writer.writerow([
                    tc.case_id,
                    tc.name,
                    tc.module,
                    tc.function_name,
                    r['result'],
                    r.get('reason', ''),
                    timestamp
                ])
        
        print(f"✅ CSV报告: {csv_file}")
        
        # HTML报告
        self.generate_html_report()
    
    def generate_html_report(self):
        """生成HTML报告"""
        html_file = REPORT_DIR / "test_report.html"
        
        # 统计
        stats = {}
        for r in self.test_results.values():
            result = r['result']
            stats[result] = stats.get(result, 0) + 1
        
        total = len(self.test_results)
        pass_count = stats.get('成功', 0)
        fail_count = stats.get('失败', 0)
        skip_count = stats.get('未执行', 0)
        not_found = stats.get('未找到', 0)
        
        pass_rate = (pass_count / total * 100) if total > 0 else 0
        
        html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>KMP CAPI 测试报告</title>
    <style>
        body {{ font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }}
        .container {{ max-width: 1400px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }}
        h1 {{ color: #333; border-bottom: 3px solid #4CAF50; padding-bottom: 10px; }}
        .summary {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; margin: 30px 0; }}
        .stat-card {{ background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px; border-radius: 8px; text-align: center; }}
        .stat-card.pass {{ background: linear-gradient(135deg, #56ab2f 0%, #a8e063 100%); }}
        .stat-card.fail {{ background: linear-gradient(135deg, #cb356b 0%, #bd3f32 100%); }}
        .stat-card h3 {{ margin: 0 0 10px 0; font-size: 14px; text-transform: uppercase; }}
        .stat-card .number {{ font-size: 32px; font-weight: bold; }}
        table {{ width: 100%; border-collapse: collapse; margin-top: 20px; table-layout: fixed; }}
        th, td {{ padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }}
        th {{ background: #f8f9fa; font-weight: 600; color: #333; }}
        tr:hover {{ background: #f8f9fa; }}
        .badge {{ padding: 4px 12px; border-radius: 12px; font-size: 12px; font-weight: bold; }}
        .badge.pass {{ background: #d4edda; color: #155724; }}
        .badge.fail {{ background: #f8d7da; color: #721c24; }}
        .badge.skip {{ background: #fff3cd; color: #856404; }}
        .badge.not-found {{ background: #e2e3e5; color: #383d41; }}
        .module-section {{ margin-top: 40px; }}
        .module-title {{ background: #667eea; color: white; padding: 10px 15px; border-radius: 4px; margin-top: 20px; }}
        .col-case {{ width: 28%; }}
        .col-func {{ width: 20%; }}
        .col-result {{ width: 120px; text-align: center; }}
        .col-reason {{ width: 52%; }}
        .nowrap {{ white-space: nowrap; }}
        code {{ word-break: break-all; white-space: normal; font-family: Menlo, Consolas, monospace; font-size: 12px; }}
    </style>
</head>
<body>
    <div class="container">
        <h1>🧪 KMP CAPI 自动化测试报告</h1>
        <p>生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
        
        <div class="summary">
            <div class="stat-card">
                <h3>总用例数</h3>
                <div class="number">{total}</div>
            </div>
            <div class="stat-card pass">
                <h3>✅ 通过</h3>
                <div class="number">{pass_count}</div>
            </div>
            <div class="stat-card fail">
                <h3>❌ 失败</h3>
                <div class="number">{fail_count}</div>
            </div>
            <div class="stat-card">
                <h3>通过率</h3>
                <div class="number">{pass_rate:.1f}%</div>
            </div>
        </div>
"""
        
        # 按模块分组
        modules = {}
        for tc in self.test_cases:
            if tc.module not in modules:
                modules[tc.module] = []
            modules[tc.module].append(tc)
        
        # 自定义模块展示顺序（Failure 放在 VersionGuard 之上，VersionGuard 最后）
        desired_order = ["RDB", "CommonEvent", "HuksKeyApi", "NetConnection", "HiAppEvent", "HiLog", "Drawing", "Failure", "VersionGuard"]
        order_index = {name: i for i, name in enumerate(desired_order)}
        sorted_modules = sorted(modules.keys(), key=lambda m: order_index.get(m, len(desired_order)))

        for module_name in sorted_modules:
            cases = modules[module_name]
            html += f"""
        <div class="module-section">
            <div class="module-title">📦 {module_name} ({len(cases)} 个用例)</div>
            <table>
                <thead>
                    <tr>
                        <th class="col-case">用例编号</th>
                        <th class="col-func">函数/常量名</th>
                        <th class="col-result">测试结果</th>
                        <th class="col-reason">说明</th>
                    </tr>
                </thead>
                <tbody>
"""
            
            for tc in cases:
                r = self.test_results.get(tc.case_id, {'result': '未运行', 'reason': ''})
                result = r['result']
                reason = r.get('reason', '')
                
                badge_class = {
                    '成功': 'pass',
                    '失败': 'fail',
                    '未执行': 'skip',
                    '未找到': 'not-found',
                    'API版本不符': 'skip'
                }.get(result, 'not-found')
                
                badge_text = {
                    '成功': '✅ 成功',
                    '失败': '❌ 失败',
                    '未执行': '⏭ 未执行',
                    '未找到': '❓ 未找到',
                    'API版本不符': '⚠️ API版本不符'
                }.get(result, result)
                
                html += f"""
                    <tr>
                        <td><code>{tc.case_id}</code></td>
                        <td><code>{tc.function_name}</code></td>
                        <td class="col-result nowrap"><span class="badge {badge_class}">{badge_text}</span></td>
                        <td class="col-reason" style="font-size: 12px; color: #666;">{reason}</td>
                    </tr>
"""
            
            html += """
                </tbody>
            </table>
        </div>
"""
        # 补充展示未在 CSV 用例中出现的模块（例如“失败场景模块”）
        extra_modules = set(self.ui_results.keys()) - set(modules.keys())
        extra_sorted = sorted(extra_modules, key=lambda m: order_index.get(m, len(desired_order)))
        for module_name in extra_sorted:
            results = self.ui_results.get(module_name, {})
            html += f"""
        <div class="module-section">
            <div class="module-title">📦 {module_name}（无 CSV 用例，基于页面清单统计）</div>
            <table>
                <thead>
                    <tr>
                        <th class="col-func">函数/常量名</th>
                        <th class="col-result">测试结果</th>
                        <th class="col-reason">说明</th>
                    </tr>
                </thead>
                <tbody>
"""
            for func, info in results.items():
                result = info.get('result', '未知')
                reason = info.get('reason', '')
                badge_class = {
                    '成功': 'pass',
                    '失败': 'fail',
                    '未执行': 'skip',
                    '未找到': 'not-found',
                    'API版本不符': 'skip'
                }.get(result, 'not-found')
                badge_text = {
                    '成功': '✅ 成功',
                    '失败': '❌ 失败',
                    '未执行': '⏭ 未执行',
                    '未找到': '❓ 未找到',
                    'API版本不符': '⚠️ API版本不符'
                }.get(result, result)
                html += f"""
                    <tr>
                        <td><code>{func}</code></td>
                        <td class="col-result nowrap"><span class="badge {badge_class}">{badge_text}</span></td>
                        <td class="col-reason" style="font-size: 12px; color: #666;">{reason}</td>
                    </tr>
"""
            html += """
                </tbody>
            </table>
        </div>
"""
        
        html += """
    </div>
</body>
</html>
"""
        
        with open(html_file, 'w', encoding='utf-8') as f:
            f.write(html)
        
        print(f"✅ HTML报告: {html_file}")


def main():
    """主函数"""
    runner = ModuleTestRunner()
    
    # 1. 解析用例
    runner.parse_csv()
    
    # 2. 运行测试
    print("\n🤖 开始自动化测试")
    print("=" * 70)
    if not runner.run_all_modules():
        print("\n❌ 测试执行失败")
        return
    
    # 3. 收集结果
    runner.collect_results()
    
    # 4. 生成报告
    runner.generate_reports()
    
    print("\n" + "="*70)
    print("🎉 测试完成！")
    print("="*70)


if __name__ == "__main__":
    main()
