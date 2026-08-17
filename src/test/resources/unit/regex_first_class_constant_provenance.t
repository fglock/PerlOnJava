use strict;
use warnings;
use threads;

print "1..4\n";
my $test = 0;
sub ok {
    my ($condition, $name) = @_;
    ++$test;
    print(($condition ? "ok" : "not ok"), " $test - $name\n");
}

use constant executable_regex => ${qr/(?{})/};

my $error = eval '"" =~ executable_regex; 1' ? '' : $@;
ok($error eq '', 'a scalar dereferenced from qr retains executable-regex provenance');

my $worker = threads->create(sub {
    return eval '"" =~ executable_regex; 1' ? '' : $@;
});
ok($worker->join eq '', 'the trusted constant provenance survives an ithread snapshot');

my $plain = '(?{})';
eval { qr/$plain/ };
ok($@ =~ /Eval-group not allowed at runtime, use re 'eval'/,
    'an ordinary string does not acquire trusted regex provenance');

my $mutated = ${qr/(?{})/};
$mutated .= '';
eval { qr/$mutated/ };
ok($@ =~ /Eval-group not allowed at runtime, use re 'eval'/,
    'ordinary scalar mutation clears trusted regex provenance');
