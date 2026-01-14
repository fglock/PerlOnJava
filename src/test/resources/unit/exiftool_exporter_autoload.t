use strict;
use warnings;

our $autoload_seen;

{
    package Exiftool::ExporterAutoload;

    use Exporter qw(import);

    our @EXPORT = qw(GetAllTags);

    sub GetAllTags;

    our $AUTOLOAD;

    sub AUTOLOAD {
        $main::autoload_seen = $AUTOLOAD;
        return 1;
    }
}

print "1..1\n";

Exiftool::ExporterAutoload->import();

my $ok = eval { GetAllTags(); 1 };
if (!$ok) {
    my $err = $@;
    $err =~ s/\s+\z//;
    print "not ok 1 - GetAllTags() should trigger AUTOLOAD (got error: $err)\n";
    exit;
}

if (defined $autoload_seen && $autoload_seen eq 'Exiftool::ExporterAutoload::GetAllTags') {
    print "ok 1 - AUTOLOAD called for imported forward-declared sub\n";
} else {
    my $seen = defined $autoload_seen ? $autoload_seen : '<undef>';
    print "not ok 1 - AUTOLOAD not called correctly (saw: $seen)\n";
}
