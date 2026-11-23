# Reranks the wide candidate pool (evalpool.json) and writes the five best passages per question
# to evalset-reranked.json, laid out like evalset-retrieved.json.
#   perl poolrank.pl        (llama.cpp server started with --reranking)
use strict; use warnings; use JSON::PP; use HTTP::Tiny; binmode STDOUT, ':utf8';
my $j = JSON::PP->new->utf8->canonical->pretty; my $http = HTTP::Tiny->new(timeout => 900);
open my $h, '<:raw', 'evalpool.json' or die; my $pool = do { local $/; $j->decode(<$h>) }; close $h;
for my $c (@{$pool->{cases}}) {
    my @p = @{$c->{passages}}; next unless @p;
    my $r = $http->post('http://127.0.0.1:8089/v1/rerank', { headers => { 'Content-Type' => 'application/json' },
        content => $j->encode({ query => $c->{question}, documents => [map { "$_->{label}\n" . substr($_->{text}, 0, 1800) } @p] }) });
    die "$r->{status} " . substr($r->{content}, 0, 300) unless $r->{success};
    $p[$_->{index}]{score} = $_->{relevance_score} for @{ $j->decode($r->{content})->{results} };
    my @top = sort { $b->{score} <=> $a->{score} } @p; my (@keep, %per, $chars);
    for my $x (@top) { last if @keep >= 5; my $doc = (split / \x{2014} /, $x->{label})[0];
        next if ($per{$doc} // 0) >= 3; next if @keep && $chars + length($x->{text}) > 3000;
        $per{$doc}++; $chars += length $x->{text}; push @keep, $x; }
    $c->{topScore} = sprintf('%.3f', $top[0]{score}) + 0;
    my $n = 0; $c->{passages} = [map { $n++; { index => $n, label => $_->{label}, text => $_->{text}, score => sprintf('%.3f', $_->{score}) + 0, was => $_->{index} } } @keep];
    my $hits = grep { my $t = lc $_->{text}; grep { index($t, lc $_) >= 0 } @{$c->{expect}} } @keep;
    printf "%s %-10s top=%.3f was=[%s] hit=%d  %s\n", $c->{id}, $c->{cat}, $top[0]{score}, join(',', map { $_->{was} } @{$c->{passages}}), $hits, substr($c->{question}, 0, 48);
}
open $h, '>:raw', 'evalset-reranked.json' or die; print $h $j->encode($pool); close $h;
