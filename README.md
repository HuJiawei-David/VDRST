# VDRST
VDRST, which its' full name called Virus DNA RNA Searching Tool. This repository demonstrate the front-end and backend code of VDRST. The Front-end is using vue.js framework, and html, css, JavaScript. Backend part used Java, and SpringBoot framework.

When a user searches for a DNA or RNA sequence number, VDRST first calls the BLAST search algorithm to perform a basic similarity search, returning multiple similar sequences. The SmithWaterman algorithm is then used to perform a fine-grained similarity comparison, returning the three most similar sequences to the platform.

You have two extra steps if you want to use this code:

Step 1: You need to install the BLAST search algorithm and database on your local computer. You can go to this interface and choose the version of BLAST that suits your operating system to download https://ftp.ncbi.nlm.nih.gov/blast/executables/blast+/LATEST/.
The version I chose is: blastn: 2.16.0+.

Step 2:After downloading BLAST, you also need to download the ref_viruses_rep_genomes virus database.
Note: The download command in macOS terminal is

update_blastdb.pl --decompress ref_viruses_rep_genomes

blastn -query query.fasta -db ref_viruses_rep_genomes -out result.txt

I am responsible for the front-end (Vue.js, javascript, html, css) and back-end (Java, SpringBoot) code of VDRST. Besides me, the Prototype of this project is designed by Tang Bingni, and for the expertise in the field of biology of this project, I would like to thank Tan Ke Qi,Zhong Wen Pei, Teh Wen Xuan, Commettant Neil Jude, Huang ZhouXiang for their help. They are working in the field of biology for a long time.  
