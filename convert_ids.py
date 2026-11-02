import re

with open(r'app\build\intermediates\runtime_symbol_list\debug\R.txt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

pattern = re.compile(r'^int (\w+) (.+?) (0x[0-9a-fA-F]+)$')

with open('ds_stable_ids_new.txt', 'w', encoding='ascii') as out:
    for line in lines:
        m = pattern.match(line.strip())
        if m:
            res_type = m.group(1)
            res_name = m.group(2)
            res_id = m.group(3)
            # skip styleable
            if res_type == 'styleable':
                continue
            # convert dots in name to dots (keep as-is)
            out.write(f'{res_type}/{res_name} = {res_id}\n')

print("Done! File created.")
