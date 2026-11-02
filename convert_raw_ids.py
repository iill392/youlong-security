import re

# Parse ds_res_ids_raw.txt
raw_ids = {}
with open('ds_res_ids_raw.txt', 'r', encoding='utf-16') as f:
    content = f.read()

# Pattern: "resource 0x7f...... type/name" (may span multiple lines)
# Normalize: join continuation lines
lines = content.split('\n')
joined = []
for line in lines:
    stripped = line.strip()
    if stripped.startswith('resource '):
        joined.append(stripped)
    elif joined and not stripped.startswith('resource '):
        # continuation line
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

# Build final stable IDs: prefer raw_ids (original DynamicSpot), fallback to current
final = {}
# First, use raw_ids for everything that exists there
for key, val in raw_ids.items():
    if key in current_ids:
        final[key] = val
    # Also include resources in raw_ids even if not in current (library resources)

# Then add current-only resources with their current IDs
for key, val in current_ids.items():
    if key not in final:
        final[key] = val

print(f"Final stable IDs: {len(final)} resources")

# Write stable IDs file
with open('app/ds_stable_ids_final.txt', 'w', encoding='ascii') as f:
    for key in sorted(final.keys()):
        f.write(f"{key} = {final[key]}\n")

print("Written to app/ds_stable_ids_final.txt")

# Check: how many layout IDs changed?
changed = 0
for key in raw_ids:
    if key in current_ids and raw_ids[key] != current_ids[key]:
        changed += 1
        if changed <= 20:
            print(f"  CHANGED: {key}: {current_ids[key]} -> {raw_ids[key]}")

print(f"Total changed: {changed}")
