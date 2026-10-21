#!/usr/bin/env bash
# PreToolUse hook (Edit|Write): chặn sửa file chứa bí mật hoặc script DB đã chạy.
# Claude Code truyền JSON qua stdin; exit 2 = chặn, nội dung stderr được gửi lại cho Claude.
# Không dùng jq vì Git Bash trên Windows thường không có sẵn.

input=$(cat)
file_path=$(printf '%s' "$input" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)
# Chuẩn hóa đường dẫn Windows: "\\" (đã escape trong JSON) hoặc "\" → "/"
file_path=$(printf '%s' "$file_path" | sed 's#\\\\#/#g; s#\\#/#g')

[ -z "$file_path" ] && exit 0

case "$file_path" in
  *.env|*.env.*|*.pem|*.key|*.p12|*.jks)
    echo "Chặn: '$file_path' là file chứa bí mật, hãy sửa thủ công." >&2
    exit 2
    ;;
  */database/oracle/setup_admin_user.sql)
    echo "Chặn: setup_admin_user.sql chỉ do SYS chạy một lần, không sửa tự động." >&2
    exit 2
    ;;
  */database/oracle/0[0-5]_*.sql)
    echo "Chặn: script 00_-05_ đã chạy; thay đổi schema phải thêm migration mới (06_ trở đi)." >&2
    exit 2
    ;;
esac

exit 0
