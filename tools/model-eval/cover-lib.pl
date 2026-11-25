my %stop = map { $_ => 1 } qw(a an and are as at be by can do does for from how i if in into is it its me my of on or should so than that the their them then there these they this to was we what when where which who why will with would you your about tell give please much many long up out off per get got use used using also any some most more very just has have had been being);
my %aspect = map { $_ => 1 } qw(treat treated treating treatment treatments symptom symptoms sign signs cause causes caused first aid manage management cure prevent prevention diagnose diagnosis best way stop help need know rate dose steps deal handle build building built make making construct assemble);
sub stem { my $w = shift; my $cut = sub { length($w) - $_[0] >= 3 ? substr($w, 0, length($w) - $_[0]) : $w };
    return $cut->(3) if $w =~ /ies$/ && length $w > 4; return $cut->(2) if $w =~ /sses$/; return $cut->(2) if $w =~ /(x|ch|sh|ss)es$/;
    return $cut->(3) if $w =~ /ing$/ && length $w > 5; return $cut->(2) if $w =~ /ed$/ && length $w > 4 && $w !~ /eed$/;
    return $cut->(1) if $w =~ /s$/ && $w !~ /(ss|us|is)$/ && length $w > 3; $w }
# Words that never name the subject: people, pronouns, generic verbs and judgements. A subject is a
# thing, place, device, condition or substance; requiring these words blocked good answers to
# "buddy cut his arm bad" and "can I put water in the radiator" (measured 2026-11-24).
my %filler = map { $_ => 1 } qw(his her him he she our us someone somebody person people guy guys buddy friend kid kids man woman bad good really put keep stay go going take find found try get safe safely okay ok right wrong term thing things way cover covers while still severe severely serious seriously mild minor major big small little quick quickly fast real worse worst without caught catch quart gallon pint);
$aspect{$_} = 1 for keys %filler;
my %astem = map { stem($_) => 1 } keys %aspect;
sub terms { my %seen; my @t = grep { !$seen{$_}++ } map { stem($_) } grep { length >= 2 && !$stop{$_} } split /[^\p{L}\p{N}]+/, lc shift;
    @t = @t[0..7] if @t > 8; my @s = grep { !$astem{$_} } @t; @s ? @s : @t }

# Same synonym groups as CheckedAnswer.SYNONYMS in the app: an official source's own word for the same thing.
our @SYN = ([qw(purif disinfect sanitiz steriliz treat)], [qw(leg arm limb extremit)], [qw(rain wet damp)], [qw(car vehicle truck auto)], [qw(clorox bleach)], [qw(tylenol paracetamol acetaminophen)], [qw(creek stream river)]);
sub present { my ($t, $text) = @_; return 1 if $text =~ /\b\Q$t\E/i;
    for my $g (@SYN) { next unless grep { index($t, $_) == 0 || index($_, $t) == 0 } @$g; return 1 if grep { $text =~ /\b\Q$_\E/i } @$g } 0 }
1;
