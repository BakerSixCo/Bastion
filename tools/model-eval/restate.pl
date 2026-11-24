# Question-restating test, step 1: a model turns each raw question into a "query plan" (JSON,
# forced to a schema), for a search that uses the plan instead of only the raw words.
#   perl restate.pl <tag> [port]        llama.cpp server already running with the model
# Writes plans/<tag>.json: { id: { plan fields..., seconds, valid } }.
# The examples in the instructions are deliberately NOT questions from the evaluation set.
use strict; use warnings; use JSON::PP; use HTTP::Tiny; use Time::HiRes qw(time); $| = 1;
my ($tag, $port) = @ARGV; $port ||= 8089;
my $j = JSON::PP->new->utf8->canonical; my $http = HTTP::Tiny->new(timeout => 900);
# EVALSET=questions-v2.json IDS=. for the larger set.
open my $h, '<:raw', ($ENV{EVALSET} || 'evalset.json') or die; my $set = do { local $/; $j->decode(<$h>) }; close $h;

my $schema = {
    type => 'object',
    properties => {
        standalone_question => { type => 'string' },
        intent => { type => 'string', enum => [qw(how_to can_i is_it_safe what_is diagnose treat dose identify procedure other)] },
        relation => { type => 'string' },
        not_about => { type => 'string' },
        keyword_query => { type => 'string' },
        semantic_query => { type => 'string' },
        alternate => { type => 'string' },
        life_threat => { type => 'boolean' },
    },
    required => [qw(standalone_question intent relation not_about keyword_query semantic_query alternate life_threat)],
};

my $system = <<'P';
You prepare a question for a search of an offline reference library (medicine, first aid, survival, vehicle repair, gardening, food preserving, radio). You do not answer the question. Reply with one JSON object and nothing else:
- standalone_question: the question restated as one clear, complete question in plain words.
- intent: how_to, can_i, is_it_safe, what_is, diagnose, treat, dose, identify, procedure or other.
- relation: what the question is really about, with the key relationship in capitals (for example what is USED FOR what, or what is done TO what).
- not_about: the look-alike subject that shares the same words but is a different question.
- keyword_query: 4 to 8 search words a reference book would use for this subject. Replace slang and brand names with the standard name. No filler words.
- semantic_query: one short phrase describing the passage that would answer it.
- alternate: a second way to word the search, using different words.
- life_threat: true only if someone could die or be badly hurt in the next minutes.
Keep every value short.

Example. Question: can I run my generator in the garage if the door is open
{"standalone_question":"Is it safe to run a portable generator inside a garage with the door open?","intent":"is_it_safe","relation":"portable generator RUNNING INSIDE a garage","not_about":"storing a generator in a garage; garage door openers","keyword_query":"portable generator carbon monoxide enclosed space garage","semantic_query":"carbon monoxide danger from running a generator indoors","alternate":"generator exhaust poisoning indoors ventilation","life_threat":true}

Example. Question: truck just clicks when i turn the key
{"standalone_question":"Why does a vehicle only click and not crank when the key is turned?","intent":"diagnose","relation":"engine DOES NOT CRANK, only a click from the starter","not_about":"clicking noise while driving; key stuck in the ignition","keyword_query":"starter solenoid click no crank weak battery","semantic_query":"causes of a single click and no crank when starting a car","alternate":"car will not turn over battery terminals starter motor","life_threat":false}

Example. Question: pt stung by bee, lips swelling, need tx
{"standalone_question":"How do I treat a person stung by a bee whose lips are swelling?","intent":"treat","relation":"allergic reaction CAUSED BY a bee sting, swelling of the face","not_about":"ordinary bee sting pain; beekeeping","keyword_query":"anaphylaxis insect sting treatment epinephrine","semantic_query":"first aid for a severe allergic reaction to an insect sting","alternate":"bee sting allergy swelling emergency management","life_threat":true}
P

my %plans;
for my $c (@{$set->{cases}}) {
    next unless $c->{id} =~ ($ENV{IDS} ? qr/$ENV{IDS}/ : qr/^r/);
    my $t0 = time;
    my $res = $http->post("http://127.0.0.1:$port/v1/chat/completions", { headers => { 'Content-Type' => 'application/json' },
        content => $j->encode({ messages => [{ role => 'system', content => $system }, { role => 'user', content => "Question: $c->{question}" }],
            temperature => 0, max_tokens => 260, cache_prompt => JSON::PP::true,
            response_format => { type => 'json_schema', json_schema => { name => 'plan', strict => JSON::PP::true, schema => $schema } } }) });
    my $s = time - $t0; my ($plan, $raw, $usage) = (undef, '', {});
    if ($res->{success}) { my $b = $j->decode($res->{content}); $raw = $b->{choices}[0]{message}{content} // ''; $usage = $b->{usage} || {}; $plan = eval { $j->decode($raw) }; }
    my $valid = (ref $plan eq 'HASH' && !grep { !defined $plan->{$_} } @{$schema->{required}}) ? 1 : 0;
    $plans{$c->{id}} = { %{ $valid ? $plan : {} }, question => $c->{question}, seconds => sprintf('%.1f', $s) + 0, valid => $valid,
        tokens_in => $usage->{prompt_tokens}, tokens_out => $usage->{completion_tokens}, ($valid ? () : (raw => substr($raw, 0, 400))) };
    printf "%s %4.0fs valid=%d out=%s | %s\n   kw: %s\n   not: %s\n", $c->{id}, $s, $valid, $usage->{completion_tokens} // '?', $c->{question}, $plan->{keyword_query} // '-', $plan->{not_about} // '-';
}
open $h, '>:raw', "plans/$tag.json" or die; print $h JSON::PP->new->utf8->canonical->pretty->encode(\%plans); close $h;
# The wordings to send through BASTION's own search, in the layout EvalSetExportTest.searchOtherWordings reads.
my %q; for my $id (keys %plans) { my $p = $plans{$id}; $q{$id} = { original => $p->{question},
    ($p->{valid} ? (keyword => $p->{keyword_query}, standalone => $p->{standalone_question}, alternate => $p->{alternate}, semantic => $p->{semantic_query}) : ()) }; }
open $h, '>:raw', "plans/$tag-queries.json" or die; print $h $j->encode(\%q); close $h;
my @v = values %plans; my @t = sort { $a <=> $b } map { $_->{seconds} } @v;
printf "valid %d/%d  seconds median %.1f max %.1f\n", scalar(grep { $_->{valid} } @v), scalar @v, $t[int($#t / 2)], $t[-1];
