use strict;
use warnings;
use threads;
use Test::More;

{
    package ThreadSnapshotCategory;
    use warnings::register;

    sub emit_warning {
        warnings::warnif('thread snapshot category payload');
    }
}

my $thread = threads->create(sub {
    my @enabled;
    {
        local $SIG{__WARN__} = sub { push @enabled, @_ };
        ThreadSnapshotCategory::emit_warning();
    }

    my @suppressed;
    {
        no warnings 'ThreadSnapshotCategory';
        local $SIG{__WARN__} = sub { push @suppressed, @_ };
        ThreadSnapshotCategory::emit_warning();
    }

    return [scalar @enabled, $enabled[0] // '', scalar @suppressed];
});

my $result = $thread->join;
is($result->[0], 1, 'thread snapshot preserves registered category enablement');
like($result->[1], qr/^thread snapshot category payload/,
    'thread warning retains its payload');
is($result->[2], 0, 'thread snapshot preserves lexical category suppression');

done_testing;
