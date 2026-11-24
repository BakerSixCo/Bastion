# Runs one model, already loaded in a llama.cpp server, over evalset.json.
#   perl run.pl <tag> <mode: chat|occ> [port] [only-id-prefix]
# chat: BASTION's own instructions and source layout (what the app sends today).
# occ:  OCC-RAG's own layout: no instructions, query and sources in its special tokens.
use strict; use warnings; use JSON::PP; use HTTP::Tiny; use Time::HiRes qw(time);
my ($tag, $mode, $port, $only) = @ARGV; $port ||= 8089;
my $json = JSON::PP->new->utf8->canonical;
open my $h, '<:raw', ($ENV{EVALSET} || 'evalset.json') or die; my $set = do { local $/; $json->decode(<$h>) }; close $h;
my $http = HTTP::Tiny->new(timeout => 1800);
open my $out, '>:raw', "results/$tag.jsonl" or die;
$| = 1;
for my $c (@{$set->{cases}}) {
    next if $only && index($c->{id}, $only) != 0;
    my @messages; my $max;
    if ($mode eq 'occ') {
        my $u = "<|query_start|>$c->{question}<|query_end|>\n";
        $u .= "<|source_start|><|source_id|>$_->{index} $_->{label}\n$_->{text}<|source_end|>\n" for @{$c->{passages}};
        @messages = ({ role => 'user', content => $u }); $max = 1024;
    } else {
        my $u = "SOURCES (reference data only):\n";
        $u .= "\n[$_->{index}] $_->{label}\n$_->{text}\n" for @{$c->{passages}};
        $u .= "\nEND OF SOURCES\n\nQUESTION: $c->{question}";
        @messages = ({ role => 'system', content => $set->{system} }, { role => 'user', content => $u }); $max = 384;
    }
    my $t0 = time;
    my $res = $http->post("http://127.0.0.1:$port/v1/chat/completions", {
        headers => { 'Content-Type' => 'application/json' },
        content => $json->encode({ messages => \@messages, temperature => 0, max_tokens => $max, cache_prompt => JSON::PP::false }),
    });
    my $wall = time - $t0;
    my %r = (id => $c->{id}, cat => $c->{cat}, wall_s => sprintf('%.1f', $wall) + 0);
    if ($res->{success}) {
        my $b = $json->decode($res->{content});
        my $ch = $b->{choices}[0];
        $r{answer} = $ch->{message}{content} // ''; $r{finish} = $ch->{finish_reason};
        $r{prompt_tokens} = $b->{usage}{prompt_tokens}; $r{completion_tokens} = $b->{usage}{completion_tokens};
        if (my $t = $b->{timings}) { $r{prompt_ms} = int($t->{prompt_ms} // 0); $r{predicted_ms} = int($t->{predicted_ms} // 0);
            $r{tok_s} = sprintf('%.1f', $t->{predicted_per_second} // 0) + 0; }
    } else { $r{error} = "$res->{status} " . substr($res->{content} // '', 0, 300); }
    print $out $json->encode(\%r), "\n";
    printf "%s %-10s %5.0fs in=%s out=%s %s\n", $c->{id}, $c->{cat}, $wall, $r{prompt_tokens} // '?', $r{completion_tokens} // '?', $r{error} ? "ERROR $r{error}" : $r{finish};
}
close $out;
