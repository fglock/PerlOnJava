use Test::More tests => 3;

{
    no warnings 'once';
    our $file = __FILE__;
    my $open = \&CORE::open;

    ok $open->('file'),
        'CORE::open accepts a literal one-argument handle name';
    my $line = <file>;
    like($line, qr/^use Test::More/,
        'CORE::open opens the corresponding named handle');
    ok(CORE::close(*file), 'CORE::open named handle closes');
}
