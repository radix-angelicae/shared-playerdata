#!/usr/bin/env python3
"""Shared Player Data —— 静态自检（沙盒里没有 javac，用它兜底）。

三类检查，前两类是过去三个版本真正踩过的坑：

  1. 括号配平
  2. 调用了但没有定义的方法（v2 的 copyOf）
  3. 用到了但没有 import 的类型（v3 的 neoforge ClientScanner）
     —— 这是上一版工具的盲区：它只查方法定义，不查类型 import

用法：
    python3 tools/jcheck.py [项目根目录]
退出码：发现「错误」时为 1；只有「需人工确认」时为 0。
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
files = sorted(p for p in ROOT.rglob("*.java") if "build/" not in str(p))

errors = []
warns = []


def strip_noise(src):
    t = re.sub(r"//.*", "", src)
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    t = re.sub(r"'''(?:[^']|'(?!''))*'''", "''''''", t, flags=re.S)
    t = re.sub(r'"(\\.|[^"\\\n])*"', '""', t)
    t = re.sub(r"'(\\.|[^'\\\n])*'", "''", t)
    return t


def blank_out(src):
    """把注释与字符串替换成等长空白（保留换行），使行号不错位。"""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i+1] == '/':
            j = src.find('\n', i); j = n if j < 0 else j
            for k in range(i, j):
                out[k] = ' '
            i = j
        elif c == '/' and i + 1 < n and src[i+1] == '*':
            j = src.find('*/', i + 2); j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
        elif c in '"\'':
            q, j = c, i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2; continue
                if src[j] == q:
                    j += 1; break
                j += 1
            for k in range(i, min(j, n)):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
        else:
            i += 1
    return ''.join(out)


# ---------------------------------------------------------------- 0. final 字段赋值
# javac 会直接拒绝，但沙盒里没有 javac，只能静态查。
# 只认「显式接收者」形态（obj.x = ... / this.x = ...），
# 裸 x = ... 通常是同名局部变量，报了全是误报。
TYPE = r'[\w.]+(?:<[^;=()]*>)?(?:\[\])*'
for f in files:
    src = f.read_text(encoding='utf-8')
    code = blank_out(src)

    fields = {}
    for m in re.finditer(
            r'^[ \t]*(?:(?:public|private|protected|static|final|transient|volatile)\s+)*'
            r'final\s+' + TYPE + r'\s+(\w+)\s*[=;]', code, flags=re.M):
        fields[m.group(1)] = code[:m.start()].count('\n') + 1
    if not fields:
        continue

    ctor_ranges = []
    for m in re.finditer(r'\b([A-Z]\w*)\s*\([^)]*\)\s*\{', code):
        depth, j = 1, m.end()
        while j < len(code) and depth:
            if code[j] == '{':
                depth += 1
            elif code[j] == '}':
                depth -= 1
            j += 1
        ctor_ranges.append((m.start(), j))

    for name, decl_line in fields.items():
        for m in re.finditer(r'(?<![\w$.])(\w+)\s*\.\s*' + re.escape(name) + r'\s*=(?!=)', code):
            recv, line = m.group(1), code[:m.start()].count('\n') + 1
            if line == decl_line:
                continue
            if recv == 'this' and any(a <= m.start() < b for a, b in ctor_ranges):
                continue
            errors.append(
                f"{f.name}:{line}: 给 final 字段 {name} 赋值（javac 会拒绝）-> "
                f"{src.split(chr(10))[line-1].strip()}")

# ---------------------------------------------------------------- 1. 括号配平
for f in files:
    t = strip_noise(f.read_text(encoding="utf-8"))
    for o, c in (("{", "}"), ("(", ")"), ("[", "]")):
        d = t.count(o) - t.count(c)
        if d:
            errors.append(f"{f.name}: 括号不配平 {o}{c} 差值 {d}")

# ------------------------------------------------- 2. 调用了但没定义的方法
MOD = r"(?:public|private|protected|static|final|abstract|synchronized|native)"
defined = set()
for f in files:
    for line in f.read_text(encoding="utf-8").split("\n"):
        s = line.strip()
        if s.startswith("@") or not re.match(MOD + r"\b", s) or "(" not in s:
            continue
        m = re.search(r"(\w+)\s*$", s[: s.index("(")])
        if m:
            defined.add(m.group(1))

EXTERNAL = set("""
if for while switch catch return new throw assert synchronized instanceof
try finally else do get put add remove contains size isEmpty clear copy
trim split equals hashCode toString length charAt substring indexOf
replace matches format valueOf parseLong parseInt parseDouble parseUUID
fromString randomUUID getName getSimpleName getDeclaredFields getFields
getMethods getMethod getDeclaredMethod getField getDeclaredField
getParameterTypes getReturnType getEnumConstants getClassLoader
getParameterCount setAccessible invoke newProxyInstance newInstance
isInstance isAssignableFrom forName cast read readByte readShort readInt
readLong readFloat readDouble readUTF readFully readAllBytes write
writeByte writeShort writeInt writeLong writeFloat writeDouble writeUTF
flush close reset print println resolve resolveSibling getParent
getFileName getRoot startsWith endsWith toLowerCase toUpperCase toPath
toFile toMillis toArray iterator hasNext next nextInt sort reverseOrder
compare compareTo sleep start setDaemon interrupt currentThread addAll
getOrDefault keySet values entrySet putIfAbsent error warn info log debug
trace name values ordinal getComponent getType getKey getValue displayClientMessage
literal getUser getProfileId getGameDir getSingleplayerServer getWorldPath
getPlayerList saveWithoutId load contains copy emptyOf ofByte ofShort ofInt
ofLong ofFloat ofDouble ofString ofByteArray ofIntArray ofLongArray ofList
ofCompound ofUuid newCompound newList isCompound isList asList asMap rawValue
type elementType uuid file size modifiedMillis orphan dir name result
embeddedOwner archived localHasData availableUuids notes stamp report
getIntArray getString getInt getCompound putInt putString putCompound
setSink init scan status listData adopt startWatcher reload savesDir
localUuid minecraft isInWorld sendMessage singleplayerServer playerDataDir
serverPlayer readNbt applyNbt install has dirOf fileOf list clearCache
backupOnce backupOwnPlayerData backupPathOf isProbablyEmpty
isProbablyEmptyCached stackTraceOf pruneCache uuidFromFileName
getMostSignificantBits getLeastSignificantBits getLastModifiedTime
createDirectories createTempDirectory isRegularFile isDirectory exists
deleteIfExists newInputStream newOutputStream newDirectoryStream walk
delete move createFile writeString readString lines count min max abs
printStackTrace append insert setLength notify notifyAll wait join yield
ofNullable orElseGet orElseThrow stream filter map collect toList
getAndSet compareAndSet getAndIncrement incrementAndGet
""".split())

KEYWORDS = {
    "int", "long", "float", "double", "boolean", "byte", "short", "char",
    "void", "true", "false", "null", "this", "super", "String", "Object",
    "Class", "Math", "System", "Thread", "UUID", "Files", "Paths", "Path",
    "Collections", "Arrays", "Optional", "List", "ArrayList", "Map",
    "HashMap", "Set", "HashSet", "Iterator", "StringBuilder", "Exception",
    "Throwable", "RuntimeException", "IOException",
    "IllegalArgumentException", "InterruptedException", "Boolean", "Integer",
    "Long", "Double", "Float", "Byte", "Short", "Runnable", "Proxy",
    "Method", "Field", "ConcurrentHashMap", "SimpleDateFormat", "Date",
    "Enum", "StringWriter", "PrintWriter", "GZIPInputStream",
    "GZIPOutputStream", "DataInputStream", "DataOutputStream",
    "BufferedInputStream", "BufferedOutputStream", "ByteArrayInputStream",
    "InputStream", "OutputStream", "StandardCopyOption",
    "AtomicMoveNotSupportedException", "DirectoryStream", "Uuids", "Tag",
    "NbtIo", "Level", "IOException",
}

for f in files:
    t = strip_noise(f.read_text(encoding="utf-8"))
    for m in re.finditer(r"(?<![\w.])([a-z][A-Za-z0-9_]*)\s*\(", t):
        n = m.group(1)
        if n in defined or n in EXTERNAL or n in KEYWORDS:
            continue
        warns.append(f"{f.name}: 疑似未定义的方法 {n}()")

# ------------------------------------------------- 3. 用到了但没 import 的类型
# 收集项目里声明的类型（类 / 接口 / 枚举）及其所属包
project_types = {}   # simple name -> set of packages
for f in files:
    src = f.read_text(encoding="utf-8")
    pkg = re.search(r"package\s+([\w.]+);", src)
    pkg = pkg.group(1) if pkg else ""
    for m in re.finditer(r"\b(?:class|interface|enum|record)\s+(\w+)", src):
        project_types.setdefault(m.group(1), set()).add(pkg)

JAVA_LANG = {
    "String", "Object", "Integer", "Long", "Boolean", "Byte", "Short",
    "Double", "Float", "Character", "Number", "Math", "System", "Thread",
    "Runnable", "Exception", "RuntimeException", "Throwable", "Error",
    "Class", "Enum", "StringBuilder", "StringBuffer", "Iterable",
    "Comparable", "Cloneable", "AutoCloseable", "Override", "Deprecated",
    "SuppressWarnings", "FunctionalInterface", "Void", "IllegalStateException",
    "UnsupportedOperationException", "NullPointerException", "OutOfMemoryError",
    "InterruptedException", "IllegalArgumentException", "IndexOutOfBoundsException",
}

PRIMITIVES = {"int", "long", "float", "double", "boolean", "byte", "short",
              "char", "void"}

for f in files:
    src = f.read_text(encoding="utf-8")
    t = strip_noise(src)
    pkg = re.search(r"package\s+([\w.]+);", src)
    pkg = pkg.group(1) if pkg else ""

    imported = {i.split(".")[-1] for i in re.findall(r"import\s+(?:static\s+)?([\w.]+);", src)}
    imported_fq = set(re.findall(r"import\s+(?:static\s+)?([\w.]+);", src))

    # 找出所有被当作类型使用的简单名：声明、cast、new、instanceof、extends/implements、泛型
    used = set()
    for m in re.finditer(r"\b([A-Z]\w*)\s+\w+\s*[=;,)\[]", t):
        used.add(m.group(1))
    for m in re.finditer(r"\(\s*([A-Z]\w*)\s*\)", t):
        used.add(m.group(1))
    for m in re.finditer(r"\bnew\s+([A-Z]\w*)\s*[(<]", t):
        used.add(m.group(1))
    for m in re.finditer(r"\binstanceof\s+([A-Z]\w*)", t):
        used.add(m.group(1))
    for m in re.finditer(r"\b(?:extends|implements)\s+([A-Z]\w*)", t):
        used.add(m.group(1))
    for m in re.finditer(r"<\s*([A-Z]\w*)\s*>", t):
        used.add(m.group(1))
    for m in re.finditer(r"\b([A-Z]\w*)\.class\b", t):
        used.add(m.group(1))
    # 静态方法调用 / 静态字段访问：Xxx.yyy(...)
    # v3 漏掉的正是这个形态（neoforge 里直接写 ClientScanner.start()）
    for m in re.finditer(r"(?<![\w.])([A-Z]\w*)\s*\.\s*[a-z]\w*\s*\(", t):
        used.add(m.group(1))

    for name in sorted(used):
        if name in PRIMITIVES or name in JAVA_LANG or name in imported:
            continue
        if name in project_types:
            # 同包或已 import 才算 OK
            if pkg in project_types[name]:
                continue
            if any(i.endswith("." + name) or i == name for i in imported_fq):
                continue
            errors.append(
                f"{f.name}: 用了项目类型 {name}（位于 {sorted(project_types[name])[0]}），"
                f"但既不在同包也没有 import"
            )
        # 外部类型（Minecraft / 加载器 API）不报：静态分析分不清它们来自哪里

# ---------------------------------------------------------------- 输出
print(f"扫描 {len(files)} 个 Java 文件，识别到 {len(defined)} 个方法定义")

if errors:
    print("\n[错误]")
    for e in errors:
        print("  ✗", e)

if warns:
    print("\n[需人工确认]")
    for w in sorted(set(warns)):
        print("  ?", w)

if not errors and not warns:
    print("\n未发现问题 ✅")

sys.exit(1 if errors else 0)
