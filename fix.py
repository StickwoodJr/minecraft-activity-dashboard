import re

filepath = 'frontend/mc-activity-heatmap-v13.html'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# We will handle conflicts one by one
blocks = re.split(r'(<<<<<<< HEAD\n.*?\n=======\n.*?\n>>>>>>> origin/main\n)', content, flags=re.DOTALL)

new_content = blocks[0]

for i in range(1, len(blocks), 2):
    conflict = blocks[i]
    head_match = re.search(r'<<<<<<< HEAD\n(.*?)\n=======\n', conflict, flags=re.DOTALL)
    main_match = re.search(r'=======\n(.*?)\n>>>>>>> origin/main\n', conflict, flags=re.DOTALL)
    
    head = head_match.group(1) if head_match else ""
    main = main_match.group(1) if main_match else ""
    
    # Conflict 1: CSS. Head has player table and tabs, Main has tooltips and modal styles.
    if i == 1:
        resolved = head + "\n" + main
    # Conflict 2: HTML Tabs vs Main's KPI row. Head added tabs and wrapped things in viewPlaytime.
    # Main added kpi-row changes (like span instead of div).
    # Since HEAD has tabs, we should keep HEAD's tabs, but use Main's KPI row inside viewPlaytime.
    elif i == 3:
        resolved = head # Just keeping HEAD's layout for now as it's structurally different
    # Conflict 3: Modal HTML. Head added In-Game stats, Main added charts.
    elif i == 5:
        resolved = head + "\n" + main # Keep both modal sections for now or just HEAD
    # Conflict 4: JS Variables. Head added playerMeta, Main added currentViewMode etc.
    elif i == 7:
        resolved = head + "\n" + main
    # Conflict 5: JS Player Meta fetch. Head added loadPlayerMeta(), Main has something else?
    elif i == 9:
        resolved = head
    # Conflict 6: JS daily parsing. 
    elif i == 11:
        resolved = head
    else:
        resolved = head # Default to HEAD

    new_content += resolved + blocks[i+1]

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(new_content)

# Copy to backend
import shutil
shutil.copyfile(filepath, 'backend/src/main/resources/web/mc-activity-heatmap-v13.html')
