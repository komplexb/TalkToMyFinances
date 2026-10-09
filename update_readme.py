import re

with open('README.md', 'r') as f:
    content = f.read()

# Remove recipe-pairing-v1-0-0
content = re.sub(r'^\s*- `recipe-pairing-v1-0-0`\n', '', content, flags=re.MULTILINE)

# Update Option A obtaining config
new_option_a = """2. **Obtain `google-services.json` using the Firebase CLI**:
   - Authenticate and select the project:
     ```sh
     npx -y firebase-tools@latest login
     npx -y firebase-tools@latest use <PROJECT_ID>
     ```
     *(For headless/remote environments, use `login --no-localhost`).*
   - List the registered apps to find your Android `<APP_ID>`:
     ```sh
     npx -y firebase-tools@latest apps:list --project <PROJECT_ID>
     ```
   - Fetch the config using the `<APP_ID>`:
     ```sh
     npx -y firebase-tools@latest apps:sdkconfig ANDROID <APP_ID> --project <PROJECT_ID> > app/google-services.json
     ```"""

content = re.sub(
    r'2\. \*\*Obtain `google-services\.json` using the Firebase CLI\*\*:(.*?)3\. \*\*Register your Debug SHA-256',
    new_option_a + '\n3. **Register your Debug SHA-256',
    content,
    flags=re.DOTALL
)

# Option B - apps list mention
content = content.replace(
    'The command above will print the `<APP_ID>` (e.g. `1:1234567890:android:abcdef123456`). Fetch the config using this ID:',
    'The command above will print the `<APP_ID>` (e.g. `1:1234567890:android:abcdef123456`). You can also retrieve it via `npx -y firebase-tools@latest apps:list`. Fetch the config using this ID:'
)

with open('README.md', 'w') as f:
    f.write(content)
