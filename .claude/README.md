# Thư mục `.claude`

Cấu hình Claude Code cho dự án (chia sẻ qua repo). Hướng dẫn chung của dự án vẫn nằm ở `CLAUDE.md` gốc.

| Đường dẫn | Vai trò | Cách dùng |
|---|---|---|
| `settings.json` | Quyền (allow/deny), biến môi trường, hooks dùng chung | Tự nạp |
| `settings.local.json` | Cấu hình cá nhân, **không commit** (đã gitignore) | Copy từ `settings.local.json.example` |
| `rules/*.md` | Quy tắc theo đường dẫn (frontmatter `paths`), chỉ nạp khi làm việc với file khớp | Tự nạp |
| `skills/<tên>/SKILL.md` | Quy trình nhiều bước; Claude tự dùng khi phù hợp | `/<tên>` hoặc để Claude tự chọn |
| `agents/*.md` | Subagent chuyên biệt (context riêng, giới hạn tool) | "dùng agent code-reviewer ..." hoặc `/agents` |
| `commands/*.md` | Slash command dạng một file (kiểu cũ, vẫn hỗ trợ) | `/run-local account-service` |
| `hooks/*.sh` | Script được `settings.json` gọi theo sự kiện | Tự chạy |
| `output-styles/*.md` | Phong cách trả lời | `/output-style` |

## Nội dung hiện có
- **Rules**: `java-code-style`, `jpa-entity`, `oracle-migration`, `kafka-saga`, `testing`, `gateway-routing`, `security`, `common-lib`, `transaction-service`, `account-service`, `notification-service`, `redis-request-reply`, `hazelcast-cache`, `observability`. Kiến trúc chi tiết nằm ở đây thay vì `CLAUDE.md` (giữ `CLAUDE.md` < 150 dòng); thêm/đổi rule thì cập nhật bảng "Tài liệu chi tiết" trong `CLAUDE.md` và `AGENTS.md`.
- **Skills**: `add-oracle-migration`, `add-redis-action`, `add-kafka-event`, `build-and-test`.
- **Agents**: `code-reviewer`, `security-auditor`, `db-migration-reviewer` (chỉ đọc).
- **Commands**: `run-local`.
- **Hook** `block-sensitive-files.sh` (PreToolUse `Edit|Write`): chặn sửa `.env`, `*.pem`, `*.key`, `*.jks`, `setup_admin_user.sql` và script `00_`–`05_` đã chạy.

## Thêm mới
- Rule: tạo `rules/<ten>.md`, khai báo `paths` (glob) trong frontmatter; bỏ `paths` để nạp mọi lúc.
- Skill: tạo `skills/<ten>/SKILL.md` với `name`, `description` (mô tả rõ *khi nào* dùng); có thể kèm file phụ trong cùng thư mục.
- Agent: tạo `agents/<ten>.md` với `name`, `description`, `tools`, `model`.
- Cấu hình chỉ cho máy mình → `settings.local.json`; cho cả nhóm → `settings.json`.
