use strict;
use warnings;
use Test::More;

# Assigning the current effective UID is safe and should remain a valid write
# even when real and effective IDs differ. Avoid changing this test process's
# privileges while still exercising the special variable setter.
plan skip_all => 'real and effective UIDs differ' if $< != $>;

my $effective_uid = $>;
my $assigned = eval {
    $> = $<;
    1;
};

ok($assigned, 'assignment to $> accepts the current real UID');
is($@, '', 'assignment to $> does not report a read-only scalar error');
is($>, $effective_uid, 'assignment preserves the effective UID');

done_testing;
