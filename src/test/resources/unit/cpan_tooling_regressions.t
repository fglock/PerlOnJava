use strict;
use warnings;

use Cwd qw(getcwd);
use ExtUtils::MakeMaker;
use File::Temp qw(tempdir tempfile);
use POSIX qw(locale_h);
use Test::More;
use Tie::File;

my $constant_warning = '';
{
    local $SIG{__WARN__} = sub { $constant_warning .= join '', @_ };
    eval q{
        package Local::KeywordConstantProbe;
        use warnings;
        use constant AUTOLOAD => 1;
        1;
    };
}
like(
    $constant_warning,
    qr/Constant name 'AUTOLOAD' is a Perl keyword/,
    'custom warning categories inherit lexical warnings',
);

{
    package Local::AliasedBooleanBase;
    use overload '0+' => sub { ${ $_[0] } }, fallback => 1;

    package Local::AliasedBoolean;
    BEGIN { *Local::AliasedBoolean:: = *Local::CanonicalBoolean::; }
    @Local::AliasedBoolean::ISA = ('Local::AliasedBooleanBase');

    package main;
    my $false = bless \(my $value = 0), 'Local::AliasedBoolean';
    is(ref($false), 'Local::CanonicalBoolean', 'bless resolves a package stash alias');
    ok(!$false, 'aliased stash inherits boolean overload through its ISA array');
    is(0 + $false, 0, 'aliased stash inherits numeric overload through its ISA array');
}

my ($fh, $filename) = tempfile();
print {$fh} "first\nsecond\nthird\nfourth\nfifth\nsixth\n";
close $fh;

tie my @records, 'Tie::File', $filename, recsep => "\n"
    or die "Could not tie $filename: $!";

my @removed = splice @records, 2, 1;
is_deeply(\@removed, ['third'], 'Tie::File splice returns only removed records');
is_deeply(
    \@records,
    [qw(first second fourth fifth sixth)],
    'Tie::File splice preserves records outside the requested range',
);
untie @records;

open my $check, '<', $filename or die "Could not read $filename: $!";
local $/;
is(
    <$check>,
    "first\nsecond\nfourth\nfifth\nsixth\n",
    'Tie::File splice flushes the complete remaining file',
);
close $check;

{
    use locale;
    my $old_locale = setlocale(LC_CTYPE);
    setlocale(LC_CTYPE, 'de_DE');
    like(chr(228), qr/^\w$/, 'locale regex character classes recognize an umlaut');
    setlocale(LC_CTYPE, $old_locale);
}

{
    my $cwd = getcwd();
    my $dist_dir = tempdir(CLEANUP => 1);
    chdir $dist_dir or die "Could not chdir to $dist_dir: $!";
    WriteMakefile(
        NAME    => 'Local::MakeMakerTargetProbe',
        VERSION => '0.001',
        PM      => {},
    );
    open my $makefile, '<', 'Makefile' or die "Could not read generated Makefile: $!";
    local $/;
    my $content = <$makefile>;
    close $makefile;
    chdir $cwd or die "Could not restore working directory to $cwd: $!";

    like(
        $content,
        qr/^install\s+::\s+pure_install\s+doc_install\b/m,
        'generated Makefile exposes the standard install dependency line',
    );
    like(
        $content,
        qr/^uninstall\s+::\s+\S+/m,
        'generated Makefile exposes the standard uninstall dependency line',
    );
}

done_testing;
