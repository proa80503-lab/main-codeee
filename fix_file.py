with open(r'C:\Users\AD\Desktop\AvatarMod\src\main\java\com\ad\avatarelements\AbilityManager.java', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Find the FIRST closing brace that ends the class (line that is just "}\n" after the last method)
# We want to keep everything up to and including line 2288 (index 2287)
# The new code ends at line 2288, so keep lines 0..2287
keep = lines[:2288]
# Ensure the file ends with exactly one closing brace and newline
if keep and keep[-1].strip() != '}':
    keep.append('}\n')

with open(r'C:\Users\AD\Desktop\AvatarMod\src\main\java\com\ad\avatarelements\AbilityManager.java', 'w', encoding='utf-8') as f:
    f.writelines(keep)

print(f"Done. Total lines: {len(keep)}")
