import re,sys
rows=[l for l in open(sys.argv[1],errors='ignore') if l.startswith('S ')]
P=[]
for l in rows:
    m=re.search(r'out ([-\d.]+) \| mc z ([-\d.]+) vz ([-\d.]+) ground (\d).*xy \(([-\d.]+) ([-\d.]+)\)',l)
    if m: P.append([float(x) for x in m.groups()])
d=[((P[i][4]-P[i-1][4])**2+(P[i][5]-P[i-1][5])**2)**.5 for i in range(1,len(P))]
for i,x in enumerate(d):
    if x>30: print(sys.argv[1],'crossing at',i,':',' '.join('%.1f'%y for y in d[max(0,i-6):i+22]))
