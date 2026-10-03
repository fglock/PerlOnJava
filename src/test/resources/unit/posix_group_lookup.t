use strict;
use warnings;
use POSIX ();
use Test::More;

my $gid = POSIX::getgid();
my @by_gid = getgrgid($gid);
if (!@by_gid) {
    plan skip_all => 'group lookup is unavailable on this platform';
}

is(0 + $by_gid[2], $gid, 'getgrgid returns the requested group ID');
is(scalar getgrgid($gid), $by_gid[0], 'scalar getgrgid returns the group name');
my @by_name = getgrnam($by_gid[0]);
is(0 + $by_name[2], $gid, 'getgrnam resolves the primary group name');
is(0 + scalar getgrnam($by_gid[0]), $gid, 'scalar getgrnam returns the group ID');
my @supplementary_groups = POSIX::getgroups();
is("$(", join(' ', POSIX::getgid(), @supplementary_groups),
    '$( includes the real group ID and supplementary group IDs');
my $numeric_warning = '';
my $numeric_gid = do {
    local $SIG{__WARN__} = sub { $numeric_warning .= $_[0] };
    0 + $(;
};
is($numeric_gid, POSIX::getgid(), 'numeric $( uses the real group ID');
is($numeric_warning, '', 'numeric $( does not warn');

setgrent();
my @scalar_names;
while (@scalar_names < 4096) {
    my $name = scalar getgrent();
    last unless defined($name) && length($name);
    push @scalar_names, $name;
}
endgrent();

setgrent();
my @list_names;
while (@list_names < 4096) {
    my @entry = getgrent();
    last unless @entry && defined($entry[0]) && length($entry[0]);
    push @list_names, $entry[0];
}
endgrent();

is_deeply(\@scalar_names, \@list_names,
    'getgrent returns the same group names in scalar and list contexts');

done_testing();
