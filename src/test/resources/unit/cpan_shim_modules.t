use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use lib 'src/main/perl/lib';

use_ok('UNIVERSAL::require');
ok('File::Spec'->require, 'UNIVERSAL::require loads a module by package name');
ok(!'This::Module::Does::Not::Exist'->require,
    'UNIVERSAL::require returns false for missing modules');
like(File::Spec->case_tolerant, qr/^[01]$/,
    'File::Spec->case_tolerant returns a numeric boolean');

use_ok('I18N::LangTags');
is_deeply(
    [ I18N::LangTags::implicate_supers('en-US') ],
    [ 'en-us', 'en' ],
    'I18N::LangTags adds superordinate tags'
);
is_deeply(
    [ I18N::LangTags::implicate_supers(qw(pt-br fr pt)) ],
    [ qw(pt-br fr pt) ],
    'I18N::LangTags keeps explicit later superordinate preferences in place'
);
is_deeply(
    [ I18N::LangTags::implicate_supers(qw(pt-br-janeiro de pt-br fr)) ],
    [ qw(pt-br-janeiro de pt-br pt fr) ],
    'I18N::LangTags lets an explicit intermediate superordinate imply its parent'
);
is(I18N::LangTags::locale2language_tag('de_DE.UTF-8'), 'de-de',
    'I18N::LangTags normalizes locale names');

use_ok('I18N::LangTags::Detect');
{
    local $ENV{LANGUAGE} = 'fr_CA:de_DE';
    local $ENV{HTTP_ACCEPT_LANGUAGE};
    my @prefs = I18N::LangTags::Detect::ambient_langprefs();
    is_deeply([ @prefs[0, 1] ], [ 'fr-ca', 'de-de' ],
        'I18N::LangTags::Detect reads LANGUAGE preferences');
}
{
    local $ENV{REQUEST_METHOD} = 'GET';
    local $ENV{HTTP_ACCEPT_LANGUAGE} = 'en-US, zh-TW';
    local $ENV{LANGUAGE};
    local $ENV{LANG} = 'C.UTF-8';
    my @prefs = I18N::LangTags::Detect::detect();
    is_deeply([ @prefs[0, 1, 2, 3] ], [ 'en-us', 'en', 'zh-tw', 'zh' ],
        'I18N::LangTags::Detect prefers HTTP language order in CGI mode');
}

use_ok('File::Remove');
{
    my $dir = tempdir(CLEANUP => 1);
    mkdir "$dir/nested" or die "mkdir: $!";
    open my $fh, '>', "$dir/nested/file.txt" or die "open: $!";
    print {$fh} "ok\n";
    close $fh or die "close: $!";
    ok(File::Remove::remove(\1, "$dir/nested"), 'File::Remove removes directories recursively');
    ok(!-e "$dir/nested", 'recursive remove deleted the directory');
}

use_ok('Devel::PPPort');
{
    my $dir = tempdir(CLEANUP => 1);
    my $file = "$dir/ppport.h";
    ok(Devel::PPPort::WriteFile($file), 'Devel::PPPort writes ppport.h');
    ok(-s $file, 'ppport.h is not empty');
}

use_ok('Module::ScanDeps');
{
    my $dir = tempdir(CLEANUP => 1);
    my $file = "$dir/Foo.pm";
    open my $fh, '>', $file or die "open: $!";
    print {$fh} "package Foo;\nuse File::Spec;\n1;\n";
    close $fh or die "close: $!";

    my $deps = Module::ScanDeps::scan_deps(files => [$file], recurse => 0);
    ok(exists $deps->{'File/Spec.pm'}, 'Module::ScanDeps records use dependencies');
    is($deps->{'File/Spec.pm'}{type}, 'module', 'dependency type is module');
}

use_ok('Tie::File');
{
    my $dir = tempdir(CLEANUP => 1);
    my $file = "$dir/tie-file.txt";
    open my $fh, '>', $file or die "open: $!";
    print {$fh} "#One\n#Two\n";
    close $fh or die "close: $!";

    tie my @lines, 'Tie::File', $file or die "tie: $!";
    is_deeply(\@lines, [ '#One', '#Two' ], 'Tie::File reads records without separators');
    for my $line (@lines) {
        $line =~ s/^#//;
    }
    untie @lines;
    open my $check, '<', $file or die "open check: $!";
    local $/;
    is(<$check>, "One\nTwo\n", 'Tie::File writes modified records back');
}

use_ok('SDBM_File');
{
    my $dir = tempdir(CLEANUP => 1);
    my $file = "$dir/simple-sdbm";
    tie my %db, 'SDBM_File', $file, 0, 0666 or die "tie: $!";
    $db{foo} = 'bar';
    $db{answer} = 42;
    is(join('', sort keys %db), 'answerfoo', 'SDBM_File iterates keys');
    untie %db;
    ok(-f $file, 'SDBM_File creates a backing file');

    tie my %db2, 'SDBM_File', $file, 0, 0666 or die "tie again: $!";
    is($db2{foo}, 'bar', 'SDBM_File reloads stored values');
    untie %db2;
}

use_ok('HTTP::Cookies');
use_ok('HTTP::Cookies::Netscape');
{
    my $jar = HTTP::Cookies::Netscape->new(file => '/tmp/perlonjava-missing-cookies.txt');
    isa_ok($jar, 'HTTP::Cookies::Netscape');
    isa_ok($jar, 'HTTP::Cookies');
}

done_testing();
