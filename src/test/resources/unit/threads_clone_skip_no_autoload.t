use strict;
use warnings;
use threads;

print "1..2\n";

{
    package CloneSkipAutoloadGuard;

    our $AUTOLOAD;
    sub AUTOLOAD {
        return if $AUTOLOAD =~ /::DESTROY\z/;
        die "AUTOLOAD must not be consulted for $AUTOLOAD";
    }
}

my $object = bless {}, 'CloneSkipAutoloadGuard';
my ($thread, $error);
{
    local $@;
    $thread = eval { threads->create(sub { ref($object) }) };
    $error = $@;
}

print $thread ? "ok 1 - snapshot ignores AUTOLOAD while probing CLONE_SKIP\n"
              : "not ok 1 - snapshot ignores AUTOLOAD while probing CLONE_SKIP: $error\n";

my $class = $thread ? $thread->join() : '';
print $class eq 'CloneSkipAutoloadGuard'
    ? "ok 2 - ordinary blessed value reaches child without CLONE_SKIP\n"
    : "not ok 2 - ordinary blessed value reaches child without CLONE_SKIP\n";
