# Finds the best-matching paragraphs in the kit's official documents, locally (no AI model, no
# tokens): so card drafters and reviewers read a page of excerpts instead of whole manuals.
#   perl tools/knowledge/find.pl "snakebite tourniquet do not" [-n 8] [-doc JTS-CPG] [-all]
# Text is extracted once per document (pdftotext, page by page) into tools/kit/.textcache/.
# Prints: document, page, score, and the paragraph exactly as extracted (quote from these).
# By default skips documents not usable for cards (the 2001 SOF handbook); -all includes them.
use strict; use warnings; use File::Basename; binmode STDOUT, ':utf8';
my ($n, $only, $all) = (8, '', 0); my @q;
while (@ARGV) { my $a = shift; if ($a eq '-n') { $n = shift } elsif ($a eq '-doc') { $only = shift } elsif ($a eq '-all') { $all = 1 } else { push @q, $a } }
die "usage: find.pl \"words\" [-n 8] [-doc name-part] [-all]\n" unless @q;
my $docs = 'tools/kit/docs'; my $cache = 'tools/kit/.textcache'; mkdir $cache unless -d $cache;
my %stop = map { $_ => 1 } qw(a an and are as at be by can do does for from how i if in is it of on or the to what when where which who why with you your my me should get this that);
sub words { my @w = grep { length > 2 && !$stop{$_} } map { lc } split /[^\p{L}\p{N}]+/, shift; @w }
sub stem { my $w = shift; $w =~ s/(ies|es|s|ing|ed)$// if length($w) > 5; $w }
my @terms = do { my %s; grep { !$s{$_}++ } map { stem($_) } words(join ' ', @q) };
die "no searchable words\n" unless @terms;

my @paras;   # [doc, page, text]
for my $p (sort glob "$docs/*") {
    my $f = basename($p); next unless $f =~ /\.(pdf|md|txt)$/i; next if $only && index(lc $f, lc $only) < 0;
    next if !$all && $f =~ /Special-Operations-Forces-Medical-Handbook-2001/;
    my $c = "$cache/$f.txt";
    if (!-f $c || -M $c > -M $p) {
        if ($f =~ /\.pdf$/i) { system('pdftotext', '-q', '-enc', 'UTF-8', $p, $c) == 0 or next }
        else { open my $i, '<:raw', $p or next; open my $o, '>:raw', $c or next; local $/; my $all = <$i>; print {$o} $all; close $o; }
    }
    open my $h, '<:utf8', $c or next; local $/; my $t = <$h>; my $page = 0;
    for my $pg (split /\f/, $t) { $page++;
        for my $para (split /\n\s*\n/, $pg) { my $x = $para; $x =~ s/[ \t]+/ /g; $x =~ s/^\s+|\s+$//g; next if length $x < 60; push @paras, [$f, $page, $x] } }
}
my $N = @paras || 1; my %df;
for my $pa (@paras) { my %seen; $seen{$_}++ for map { stem($_) } words($pa->[2]); $df{$_}++ for grep { $seen{$_} } @terms }
my @scored;
for my $pa (@paras) {
    my %tf; $tf{$_}++ for map { stem($_) } words($pa->[2]);
    my ($s, $hit) = (0, 0); for my $t (@terms) { next unless $tf{$t}; $hit++; $s += (1 + log $tf{$t}) * log(1 + $N / ($df{$t} || 1)) }
    next unless $hit; $s *= $hit / @terms;   # favour paragraphs holding more of the words
    $s /= 1 + length($pa->[2]) / 2000;       # and not just long ones
    push @scored, [$s, @$pa];
}
@scored = sort { $b->[0] <=> $a->[0] } @scored;
printf "%d paragraphs searched for [%s]\n", scalar @paras, join ' ', @terms;
for my $r (@scored[0 .. ($n < @scored ? $n - 1 : $#scored)]) { printf "\n== %s  p.%d  (%.1f)\n%s\n", $r->[1], $r->[2], $r->[0], $r->[3] }
