import os

files = [
    'src/main/java/com/auracademic/backend/config/SecurityConfig.java',
    'src/main/java/com/auracademic/backend/controller/AuthController.java',
    'src/main/java/com/auracademic/backend/controller/UserController.java',
    'src/main/java/com/auracademic/backend/security/JwtAuthFilter.java',
    'src/main/java/com/auracademic/backend/service/AuditLogService.java',
    'src/main/java/com/auracademic/backend/service/AuthService.java',
    'src/main/java/com/auracademic/backend/service/EmailService.java',
    'src/main/java/com/auracademic/backend/service/UserService.java'
]

for fpath in files:
    if os.path.exists(fpath):
        with open(fpath, 'r', encoding='utf-8') as f:
            content = f.read()
        content = content.replace('@RequiredArgsConstructor\n', '')
        content = content.replace('@RequiredArgsConstructor', '')
        with open(fpath, 'w', encoding='utf-8') as f:
            f.write(content)