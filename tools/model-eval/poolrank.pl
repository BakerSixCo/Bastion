# Reranks the wide candidate pool (evalpool.json: 30 candidates per question from BASTION's own
# search) and writes the five best passages per question to evalset-reranked.json, laid out like
# evalset-retrieved.json. Progress is saved after every question (results/poolrank.jsonl), so a
# stopped run picks up where it left off.
#   perl poolrank.pl        (llama.cpp server started with --reranking on port 8089)
use strict; use warnings; use JSON::PP; use HTTP::Tiny; use Time::HiRes qw(time);
binmode STDOUT, ':utf8'; $| = 1;
my $j = JSON::PP->new->utf8->canonical; my $http = HTTP::Tiny->new(timeout => 1800);
open my $h, '<:raw', 'evalpool.json' or die; my $pool = do { local $/; $j->decode(<$h>) }; close $h;
my %done;
if (open my $d, '<:raw', 'results/poolrank.jsonl') { while (<$d>) { my $r = $j->decode($_); $done{$r->{id}} = $r } close $d }
open my $log, '>>:raw', 'results/poolrank.jsonl' or die; select((select($log), $| = 1)[0]);
for my $c (@{$pool->{cases}}) {
    my @p = @{$c->{passages}}; next unless @p;
    my $rec = $done{$c->{id}};
    unless ($rec) {
        my $t0 = time;
        my $r = $http->post('http://127.0.0.1:8089/v1/rerank', { headers => { 'Content-Type' => 'application/json' },
            content => $j->encode({ query => $c->{question}, documents => [map { "$_->{label}\n" . substr($_->{text}, 0, 1800) } @p] }) });
        die "$r->{status} " . substr($r->{content}, 0, 300) unless $r->{success};
        my @s; $s[$_->{index}] = $_->{relevance_score} for @{ $j->decode($r->{content})->{results} };
        $rec = { id => $c->{id}, seconds => sprintf('%.1f', time - $t0) + 0, passages => scalar(@p), scores => [map { sprintf('%.4f', $_ // 0) + 0 } @s] };
        print $log $j->encode($rec), "\n";
    }
    $p[$_]{score} = $rec->{scores}[$_] for 0 .. $#p;
    my @top = sort { $b->{score} <=> $a->{score} } @p; my (@keep, %per); my $chars = 0;
    for my $x (@top) { last if @keep >= 5; my $doc = (split / \x{2014} /, $x->{label})[0];
        next if ($per{$doc} // 0) >= 3; next if @keep && $chars + length($x->{text}) > 3000;
        $per{$doc}++; $chars += length $x->{text}; push @keep, $x; }
    $c->{topScore} = $top[0]{score}; my $n = 0;
    $c->{passages} = [map { $n++; { index => $n, label => $_->{label}, text => $_->{text}, score => $_->{score}, was => $_->{index} } } @keep];
    my $hits = grep { my $t = lc $_->{text}; grep { index($t, lc $_) >= 0 } @{$c->{expect}} } @keep;
    printf "%s %-10s %5.0fs top=%.3f was=[%s] hit=%d  %s\n", $c->{id}, $c->{cat}, $rec->{seconds}, $top[0]{score}, join(',', map { $_->{was} } @{$c->{passages}}), $hits, substr($c->{question}, 0, 48);
}
open $h, '>:raw', 'evalset-reranked.json' or die; print $h JSON::PP->new->utf8->canonical->pretty->encode($pool); close $h;
print "WROTE evalset-reranked.json\n";
