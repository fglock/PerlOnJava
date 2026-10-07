use Test::More tests => 9;

my $tie = \&CORE::tie;
my $tied = \&CORE::tied;
my $sysread = \&CORE::sysread;
my $umask = \&CORE::umask;

for my $bad (1, \&CORE::time) {
    eval { $tie->($bad, 'SomeClass') };
    like($@, qr/^Type of arg 1 to &CORE::tie must be reference to one of \[\$\@%\*\]/,
        'CORE::tie rejects an invalid target');
    eval { $tied->($bad) };
    like($@, qr/^Type of arg 1 to &CORE::tied must be reference to one of \[\$\@%\*\]/,
        'CORE::tied rejects an invalid target');
}

for my $bad ([], 1, bless([], 'ScalarOverload')) {
    eval { $sysread->(undef, $bad, 1) };
    like($@, qr/^Type of arg 2 to &CORE::sysread must be scalar reference/,
        'CORE::sysread rejects a non-scalar reference');
}

my $previous_mask = $umask->();
ok(defined $previous_mask && $previous_mask =~ /^\d+$/, 'CORE::umask coderef can query current mask');
my $new_mask = $umask->($previous_mask);
ok(defined $new_mask && $new_mask =~ /^\d+$/, 'CORE::umask coderef can set a mask');
