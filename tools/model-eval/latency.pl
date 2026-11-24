# Timing, token and memory figures from a device run (BaselineEvalTest) or a PC run.
#   perl latency.pl results/<tag>.jsonl [results/<retrieval>.jsonl]
use strict; use warnings; use JSON::PP;
my $j = JSON::PP->new->utf8;
sub rows { open my $h, '<:raw', $_[0] or die "$_[0]: $!"; my @r; while (<$h>) { push @r, $j->decode($_) } @r }
sub pct { my ($p, @v) = @_; @v = sort { $a <=> $b } grep { defined } @v; return '-' unless @v; my $i = int($p / 100 * $#v + 0.999999); $i = $#v if $i > $#v; $v[$i] }
sub line { my ($name, $unit, @v) = @_; @v = grep { defined } @v; printf "| %s | %s | %s | %s | %s | %d |\n", $name, (map { my $x = pct($_, @v); $x eq '-' ? '-' : sprintf('%.1f %s', $x, $unit) } 50, 95), (@v ? sprintf('%.1f %s', (sort { $a <=> $b } @v)[0], $unit) : '-'), (@v ? sprintf('%.1f %s', (sort { $b <=> $a } @v)[0], $unit) : '-'), scalar @v; }
my @r = rows($ARGV[0]);
print "| Measure | p50 | p95 | min | max | n |\n|---|---|---|---|---|---|\n";
line('Time to first word', 's', map { defined $_->{prompt_ms} ? $_->{prompt_ms} / 1000 : undef } @r);
line('Whole answer', 's', map { $_->{wall_s} } @r);
line('Reading speed (prefill)', 'tok/s', map { $_->{prefill_tps} } @r);
line('Writing speed (decode)', 'tok/s', map { $_->{tok_s} } @r);
line('Tokens in', 'tok', map { $_->{prompt_tokens} } @r);
line('Tokens out', 'tok', map { $_->{completion_tokens} } @r);
line('App memory (RSS)', 'MB', map { $_->{rss_mb} } @r);
line('Device memory still free', 'MB', map { $_->{avail_mb} } @r);
printf "cut off at the length limit: %d; errors: %d; prompts over 1664 tokens (context 2048 minus 384 for the answer): %d\n",
    scalar(grep { ($_->{finish} // '') eq 'length' } @r), scalar(grep { $_->{error} } @r), scalar(grep { ($_->{prompt_tokens} // 0) > 1664 } @r);
if ($ARGV[1]) { my @s = grep { $_->{id} ne 'open' } rows($ARGV[1]); my ($open) = grep { $_->{id} eq 'open' } rows($ARGV[1]);
    print "\n| Search | p50 | p95 | min | max | n |\n|---|---|---|---|---|---|\n";
    line('First time a question is searched', 'ms', map { $_->{first_ms} } @s); line('Same question again', 'ms', map { $_->{repeat_ms} } @s);
    printf "opening the references at start: %.0f ms for %s reference files\n", $open->{first_ms}, $open->{references} if $open; }
