import re

# Parse ds_res_ids_raw.txt (UTF-16)
raw_ids = {}
with open('ds_res_ids_raw.txt', 'r', encoding='utf-16') as f:
    content = f.read()

lines = content.split('\n')
joined = []
for line in lines:
    stripped = line.strip()
    if stripped.startswith('resource '):
        joined.append(stripped)
    elif joined and not stripped.startswith('resource '):
        joined[-1] += stripped

pattern = re.compile(r'^resource (0x7f[0-9a-fA-F]{6}) (\w+)/([\w.]+)$')
for line in joined:
    m = pattern.match(line)
    if m:
        raw_ids[f"{m.group(2)}/{m.group(3)}"] = m.group(1)

print(f"Parsed {len(raw_ids)} resources from ds_res_ids_raw.txt")

# Parse current R.txt
current_ids = {}
with open(r'app\build\intermediates\runtime_symbol_list\debug\R.txt', 'r', encoding='utf-8') as f:
    for line in f:
        m = re.match(r'^int (\w+) (.+?) (0x7f[0-9a-fA-F]{6})$', line.strip())
        if m:
            res_type, res_name, res_id = m.groups()
            if res_type == 'styleable':
                continue
            current_ids[f"{res_type}/{res_name}"] = res_id

print(f"Parsed {len(current_ids)} resources from R.txt")

# Build stable IDs with package prefix: com.youlong.tool:type/name = id
# Use raw_ids where available, fallback to current_ids
final = {}
for key, val in current_ids.items():
    if key in raw_ids:
        final[key] = raw_ids[key]
    else:
        final[key] = val

print(f"Final stable IDs: {len(final)} resources")

# Write with com.youlong.tool: prefix
with open('app/ds_stable_ids_v2.txt', 'w', encoding='ascii') as f:
    for key in sorted(final.keys()):
        f.write(f"com.youlong.tool:{key} = {final[key]}\n")

print("Written to app/ds_stable_ids_v2.txt")

# Verify activity_home_new
if 'layout/activity_home_new' in final:
    print(f"activity_home_new -> {final['layout/activity_home_new']}")

# Count changed
changed = 0
for key in raw_ids:
    if key in current_ids and raw_ids[key] != current_ids[key]:
        changed += 1
print(f"Total changed: {changed}")
