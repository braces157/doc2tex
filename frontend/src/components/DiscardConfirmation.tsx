import { useRef, useState } from 'react'
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog'

export function useDiscardConfirmation(dirty: boolean) {
  const [open, setOpen] = useState(false)
  const resolver = useRef<((answer: boolean) => void) | null>(null)
  const answer = (value: boolean) => { resolver.current?.(value); resolver.current = null; setOpen(false) }
  const confirmDiscard = () => !dirty ? Promise.resolve(true) : new Promise<boolean>(resolve => {
    resolver.current = resolve; setOpen(true)
  })
  const dialog = <AlertDialog open={open} onOpenChange={value => { if (!value) answer(false) }}>
    <AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Keep your edits?</AlertDialogTitle><AlertDialogDescription>You have unsaved changes in the LaTeX source. Save them before leaving, or discard them to continue.</AlertDialogDescription></AlertDialogHeader><AlertDialogFooter><AlertDialogCancel onClick={() => answer(false)}>Keep editing</AlertDialogCancel><AlertDialogAction onClick={() => answer(true)}>Discard changes</AlertDialogAction></AlertDialogFooter></AlertDialogContent>
  </AlertDialog>
  return { confirmDiscard, dialog }
}
