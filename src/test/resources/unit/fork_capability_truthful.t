use strict;
use warnings;
use Config;

print "1..1\n";

my $advertises_fork = $Config{d_fork} || $Config{d_pseudofork};
if (!$advertises_fork) {
    print "ok 1 - Config does not advertise unavailable fork support\n";
    exit;
}

my $pid = fork;
if (!defined $pid) {
    print "not ok 1 - advertised fork capability creates a child\n";
    exit;
}
if ($pid == 0) {
    exit;
}
waitpid $pid, 0;
print "ok 1 - advertised fork capability creates a child\n";
