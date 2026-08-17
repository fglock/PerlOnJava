use strict;
use warnings;
use threads;
use re 'eval';

print "1..4\n";
my $test = 0;
sub ok {
    my ($condition, $name) = @_;
    ++$test;
    print(($condition ? "ok" : "not ok"), " $test - $name\n");
}

sub match_dynamic_digit {
    my $nine = 9;
    my $pattern = '(??{ "$nine" })';
    return '9' =~ qr/$pattern/ ? $& : '';
}

sub match_dynamic_word {
    my $word = 'thread';
    my $pattern = qr/(??{ quotemeta($word) })/;
    return 'thread' =~ $pattern ? $& : '';
}

sub match_dynamic_unicode {
    my $unicode = "\x{3bb}";
    my $pattern = '(??{ quotemeta($unicode) })';
    return $unicode =~ qr/$pattern/u ? $& : '';
}

# The child calls every named sub before the parent does. This exercises lazy
# CV materialization after the runtime snapshot, not an already-warmed CODE.
my $worker = threads->create(sub {
    return join('|', match_dynamic_digit(), match_dynamic_word(),
        match_dynamic_unicode());
});
ok($worker->join eq "9|thread|\x{3bb}",
    'child-first lazy named subs retain lexicals used by runtime regex code');
ok(match_dynamic_digit() eq '9', 'parent dynamic string pattern remains correct');
ok(match_dynamic_word() eq 'thread', 'parent dynamic qr pattern remains correct');
ok(match_dynamic_unicode() eq "\x{3bb}", 'Unicode runtime regex capture remains correct');
